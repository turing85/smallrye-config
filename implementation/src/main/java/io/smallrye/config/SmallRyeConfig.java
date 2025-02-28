/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.config;

import static io.smallrye.config.ConfigMappingLoader.getConfigMappingClass;
import static io.smallrye.config.ConfigSourceInterceptor.EMPTY;
import static io.smallrye.config.Converters.newCollectionConverter;
import static io.smallrye.config.Converters.newMapConverter;
import static io.smallrye.config.Converters.newOptionalConverter;
import static io.smallrye.config.common.utils.StringUtil.unindexed;
import static io.smallrye.config.common.utils.StringUtil.unquoted;
import static java.util.stream.Collectors.toList;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.Converter;

import io.smallrye.common.annotation.Experimental;
import io.smallrye.config.SmallRyeConfigBuilder.InterceptorWithPriority;
import io.smallrye.config._private.ConfigLogging;
import io.smallrye.config._private.ConfigMessages;
import io.smallrye.config.common.utils.StringUtil;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class SmallRyeConfig implements Config, Serializable {
    public static final String SMALLRYE_CONFIG_PROFILE = "smallrye.config.profile";
    public static final String SMALLRYE_CONFIG_PROFILE_PARENT = "smallrye.config.profile.parent";
    public static final String SMALLRYE_CONFIG_LOCATIONS = "smallrye.config.locations";
    public static final String SMALLRYE_CONFIG_MAPPING_VALIDATE_UNKNOWN = "smallrye.config.mapping.validate-unknown";
    public static final String SMALLRYE_CONFIG_LOG_VALUES = "smallrye.config.log.values";

    private static final long serialVersionUID = 8138651532357898263L;

    private final ConfigSources configSources;
    private final Map<Type, Converter<?>> converters;
    private final Map<Type, Converter<Optional<?>>> optionalConverters = new ConcurrentHashMap<>();

    private final ConfigValidator configValidator;
    private final Map<Class<?>, Map<String, ConfigMappingObject>> mappings;

    SmallRyeConfig(SmallRyeConfigBuilder builder) {
        // This needs to be executed before everything else to make sure that defaults from mappings are available to all sources
        ConfigMappingProvider mappingProvider = builder.getMappingsBuilder().build();
        this.configSources = new ConfigSources(builder, this);
        this.converters = buildConverters(builder);
        this.configValidator = builder.getValidator();
        this.mappings = new ConcurrentHashMap<>(mappingProvider.mapConfiguration(this));
    }

    private Map<Type, Converter<?>> buildConverters(final SmallRyeConfigBuilder builder) {
        final Map<Type, SmallRyeConfigBuilder.ConverterWithPriority> convertersToBuild = new HashMap<>(builder.getConverters());

        if (builder.isAddDiscoveredConverters()) {
            for (Converter<?> converter : builder.discoverConverters()) {
                Type type = Converters.getConverterType(converter.getClass());
                if (type == null) {
                    throw ConfigMessages.msg.unableToAddConverter(converter);
                }
                SmallRyeConfigBuilder.addConverter(type, converter, convertersToBuild);
            }
        }

        final ConcurrentHashMap<Type, Converter<?>> converters = new ConcurrentHashMap<>(Converters.ALL_CONVERTERS);
        for (Map.Entry<Type, SmallRyeConfigBuilder.ConverterWithPriority> entry : convertersToBuild.entrySet()) {
            converters.put(entry.getKey(), entry.getValue().getConverter());
        }
        converters.put(ConfigValue.class, Converters.CONFIG_VALUE_CONVERTER);

        return converters;
    }

    @Override
    public <T> List<T> getValues(final String name, final Class<T> propertyType) {
        return getValues(name, propertyType, ArrayList::new);
    }

    public <T, C extends Collection<T>> C getValues(String name, Class<T> itemClass, IntFunction<C> collectionFactory) {
        return getValues(name, requireConverter(itemClass), collectionFactory);
    }

    public <T, C extends Collection<T>> C getValues(String name, Converter<T> converter, IntFunction<C> collectionFactory) {
        try {
            return getValue(name, newCollectionConverter(converter, collectionFactory));
        } catch (NoSuchElementException e) {
            return getIndexedValues(name, converter, collectionFactory);
        }
    }

    public <T, C extends Collection<T>> C getIndexedValues(String name, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(name));
        }

        final C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            collection.add(getValue(indexedProperty, converter));
        }

        return collection;
    }

    public List<String> getIndexedProperties(final String property) {
        List<String> indexedProperties = new ArrayList<>();
        for (String propertyName : this.getPropertyNames()) {
            if (propertyName.startsWith(property) && propertyName.length() > property.length()) {
                int indexStart = property.length();
                if (propertyName.charAt(indexStart) == '[') {
                    int indexEnd = propertyName.indexOf(']', indexStart);
                    if (indexEnd != -1 && propertyName.charAt(propertyName.length() - 1) != '.'
                            && StringUtil.isNumeric(propertyName, indexStart + 1, indexEnd)) {
                        indexedProperties.add(propertyName);
                    }
                }
            }
        }
        Collections.sort(indexedProperties);
        return indexedProperties;
    }

    public List<Integer> getIndexedPropertiesIndexes(final String property) {
        Set<Integer> indexes = new HashSet<>();
        for (String propertyName : this.getPropertyNames()) {
            if (propertyName.startsWith(property) && propertyName.length() > property.length()) {
                int indexStart = property.length();
                if (propertyName.charAt(indexStart) == '[') {
                    int indexEnd = propertyName.indexOf(']', indexStart);
                    if (indexEnd != -1 && propertyName.charAt(propertyName.length() - 1) != '.'
                            && StringUtil.isNumeric(propertyName, indexStart + 1, indexEnd)) {
                        indexes.add(Integer.parseInt(propertyName.substring(indexStart + 1, indexEnd)));
                    }
                }
            }
        }
        List<Integer> sortIndexes = new ArrayList<>(indexes);
        Collections.sort(sortIndexes);
        return sortIndexes;
    }

    /**
     * Return the content of the direct sub properties as the requested type of Map.
     *
     * @param name The configuration property name
     * @param keyClass the type into which the keys should be converted
     * @param valueClass the type into which the values should be converted
     * @param <K> the key type
     * @param <V> the value type
     * @return the resolved property value as an instance of the requested Map (not {@code null})
     * @throws IllegalArgumentException if a key or a value cannot be converted to the specified types
     * @throws NoSuchElementException if no direct sub properties could be found.
     */
    public <K, V> Map<K, V> getValues(String name, Class<K> keyClass, Class<V> valueClass) {
        return getValues(name, requireConverter(keyClass), requireConverter(valueClass));
    }

    public <K, V> Map<K, V> getValues(String name, Converter<K> keyConverter, Converter<V> valueConverter) {
        return getValues(name, keyConverter, valueConverter, HashMap::new);
    }

    /**
     * Return the content of the direct sub properties as the requested type of Map.
     *
     * @param name The configuration property name
     * @param keyConverter The converter to use for the keys.
     * @param valueConverter The converter to use for the values.
     * @param <K> The type of the keys.
     * @param <V> The type of the values.
     * @return the resolved property value as an instance of the requested Map or {@code null} if it could not be found.
     * @throws IllegalArgumentException if a key or a value cannot be converted to the specified types
     * @throws NoSuchElementException if no direct sub properties could be found.
     */
    public <K, V> Map<K, V> getValues(
            String name,
            Converter<K> keyConverter,
            Converter<V> valueConverter,
            IntFunction<Map<K, V>> mapFactory) {
        try {
            return getValue(name, newMapConverter(keyConverter, valueConverter, mapFactory));
        } catch (NoSuchElementException e) {
            Map<String, String> mapKeys = getMapKeys(name);
            if (mapKeys.isEmpty()) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(name));
            }

            Map<K, V> map = mapFactory.apply(mapKeys.size());
            for (Map.Entry<String, String> entry : mapKeys.entrySet()) {
                map.put(convertValue(ConfigValue.builder().withName(entry.getKey()).withValue(entry.getKey()).build(),
                        keyConverter), getValue(entry.getValue(), valueConverter));
            }
            return map;
        }
    }

    public <K, V, C extends Collection<V>> Map<K, C> getValues(
            String name,
            Class<K> keyClass,
            Class<V> valueClass,
            IntFunction<C> collectionFactory) {
        return getValues(name, requireConverter(keyClass), requireConverter(valueClass), HashMap::new, collectionFactory);
    }

    public <K, V, C extends Collection<V>> Map<K, C> getValues(
            String name,
            Converter<K> keyConverter,
            Converter<V> valueConverter,
            IntFunction<Map<K, C>> mapFactory,
            IntFunction<C> collectionFactory) {
        try {
            return getValue(name,
                    newMapConverter(keyConverter, newCollectionConverter(valueConverter, collectionFactory), mapFactory));
        } catch (NoSuchElementException e) {
            Map<String, String> mapCollectionKeys = getMapIndexedKeys(name);
            if (mapCollectionKeys.isEmpty()) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(name));
            }

            Map<K, C> map = mapFactory.apply(mapCollectionKeys.size());
            for (Map.Entry<String, String> entry : mapCollectionKeys.entrySet()) {
                map.put(convertValue(ConfigValue.builder().withName(entry.getKey()).withValue(entry.getKey()).build(),
                        keyConverter), getValues(entry.getValue(), valueConverter, collectionFactory));
            }
            return map;
        }
    }

    public Map<String, String> getMapKeys(final String name) {
        Map<String, String> mapKeys = new HashMap<>();
        for (String propertyName : getPropertyNames()) {
            if (propertyName.length() > name.length() + 1
                    && (name.isEmpty() || propertyName.charAt(name.length()) == '.')
                    && propertyName.startsWith(name)) {
                String key = unquoted(propertyName, name.isEmpty() ? 0 : name.length() + 1);
                mapKeys.put(key, propertyName);
            }
        }
        return mapKeys;
    }

    public Map<String, String> getMapIndexedKeys(final String name) {
        Map<String, String> mapKeys = new HashMap<>();
        for (String propertyName : getPropertyNames()) {
            if (propertyName.length() > name.length() + 1
                    && (name.isEmpty() || propertyName.charAt(name.length()) == '.')
                    && propertyName.startsWith(name)) {
                String unindexedName = unindexed(propertyName);
                String key = unquoted(unindexedName, name.isEmpty() ? 0 : name.length() + 1);
                mapKeys.put(key, unindexedName);
            }
        }
        return mapKeys;
    }

    @Override
    public <T> T getValue(String name, Class<T> aClass) {
        return getValue(name, requireConverter(aClass));
    }

    /**
     *
     * This method handles calls from both {@link Config#getValue} and {@link Config#getOptionalValue}.<br>
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String name, Converter<T> converter) {
        ConfigValue configValue = getConfigValue(name);
        if (Converters.CONFIG_VALUE_CONVERTER.equals(converter)) {
            return (T) configValue.noProblems();
        }

        if (converter instanceof Converters.OptionalConverter<?>) {
            if (Converters.CONFIG_VALUE_CONVERTER.equals(
                    ((Converters.OptionalConverter<?>) converter).getDelegate())) {
                return (T) Optional.of(configValue.noProblems());
            }
        }

        return convertValue(configValue, converter);
    }

    /**
     * This method handles converting values for both CDI injections and programatical calls.<br>
     * <br>
     *
     * Calls for converting non-optional values ({@link Config#getValue} and "Injecting Native Values")
     * should throw an {@link Exception} for each of the following:<br>
     *
     * 1. {@link IllegalArgumentException} - if the property cannot be converted by the {@link Converter} to the specified type
     * <br>
     * 2. {@link NoSuchElementException} - if the property is not defined <br>
     * 3. {@link NoSuchElementException} - if the property is defined as an empty string <br>
     * 4. {@link NoSuchElementException} - if the {@link Converter} returns {@code null} <br>
     * <br>
     *
     * Calls for converting optional values ({@link Config#getOptionalValue} and "Injecting Optional Values")
     * should only throw an {@link Exception} for #1 ({@link IllegalArgumentException} when the property cannot be converted to
     * the specified type).
     */
    public <T> T convertValue(ConfigValue configValue, Converter<T> converter) {
        if (configValue.hasProblems()) {
            // TODO - Maybe it will depend on the problem, but we only get the expression NoSuchElement here for now
            if (Converters.isOptionalConverter(converter)) {
                configValue = configValue.noProblems();
            } else {
                ConfigValidationException.Problem problem = configValue.getProblems().get(0);
                Optional<RuntimeException> exception = problem.getException();
                if (exception.isPresent()) {
                    throw exception.get();
                }
            }
        }

        final T converted;

        if (configValue.getValue() != null) {
            try {
                converted = converter.convert(configValue.getValue());
            } catch (IllegalArgumentException e) {
                throw ConfigMessages.msg.converterException(e, configValue.getNameProfiled(), configValue.getValue(),
                        e.getLocalizedMessage()); // 1
            }
        } else {
            try {
                // See if the Converter is designed to handle a missing (null) value i.e. Optional Converters
                converted = converter.convert("");
            } catch (IllegalArgumentException ignored) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(configValue.getNameProfiled())); // 2
            }
        }

        if (converted == null) {
            if (configValue.getValue() == null) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(configValue.getNameProfiled())); // 2
            } else if (configValue.getValue().isEmpty()) {
                throw ConfigMessages.msg.propertyEmptyString(configValue.getNameProfiled(), converter.getClass().getTypeName()); // 3
            } else {
                throw ConfigMessages.msg.converterReturnedNull(configValue.getNameProfiled(), configValue.getValue(),
                        converter.getClass().getTypeName()); // 4
            }
        }

        return converted;
    }

    public ConfigValue getConfigValue(String name) {
        final ConfigValue configValue = configSources.getInterceptorChain().proceed(name);
        return configValue != null ? configValue : ConfigValue.builder().withName(name).build();
    }

    /**
     * Get the <em>raw value</em> of a configuration property.
     *
     * @param name the property name (must not be {@code null})
     * @return the raw value, or {@code null} if no property value was discovered for the given property name
     */
    public String getRawValue(String name) {
        final ConfigValue configValue = getConfigValue(name);
        return configValue != null ? configValue.getValue() : null;
    }

    @Override
    public <T> Optional<T> getOptionalValue(String name, Class<T> aClass) {
        return getValue(name, getOptionalConverter(aClass));
    }

    public <T> Optional<T> getOptionalValue(String name, Converter<T> converter) {
        return getValue(name, newOptionalConverter(converter));
    }

    public <T> Optional<List<T>> getOptionalValues(final String propertyName, final Class<T> propertyType) {
        return getOptionalValues(propertyName, propertyType, ArrayList::new);
    }

    public <T, C extends Collection<T>> Optional<C> getOptionalValues(String name, Class<T> itemClass,
            IntFunction<C> collectionFactory) {
        return getOptionalValues(name, requireConverter(itemClass), collectionFactory);
    }

    public <T, C extends Collection<T>> Optional<C> getOptionalValues(String name, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        Optional<C> optionalValue = getOptionalValue(name, newCollectionConverter(converter, collectionFactory));
        if (optionalValue.isPresent()) {
            return optionalValue;
        } else {
            return getIndexedOptionalValues(name, converter, collectionFactory);
        }
    }

    public <T, C extends Collection<T>> Optional<C> getIndexedOptionalValues(String name, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            return Optional.empty();
        }

        final C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            final Optional<T> optionalValue = getOptionalValue(indexedProperty, converter);
            optionalValue.ifPresent(collection::add);
        }

        if (!collection.isEmpty()) {
            return Optional.of(collection);
        }

        return Optional.empty();
    }

    /**
     * Return the content of the direct sub properties as the requested type of Map.
     *
     * @param name The configuration property name
     * @param keyClass the type into which the keys should be converted
     * @param valueClass the type into which the values should be converted
     * @param <K> the key type
     * @param <V> the value type
     * @return the resolved property value as an instance of the requested Map (not {@code null})
     * @throws IllegalArgumentException if a key or a value cannot be converted to the specified types
     */
    public <K, V> Optional<Map<K, V>> getOptionalValues(String name, Class<K> keyClass, Class<V> valueClass) {
        return getOptionalValues(name, requireConverter(keyClass), requireConverter(valueClass));
    }

    public <K, V> Optional<Map<K, V>> getOptionalValues(String name, Converter<K> keyConverter, Converter<V> valueConverter) {
        return getOptionalValues(name, keyConverter, valueConverter, HashMap::new);
    }

    public <K, V> Optional<Map<K, V>> getOptionalValues(String name, Converter<K> keyConverter, Converter<V> valueConverter,
            IntFunction<Map<K, V>> mapFactory) {
        Optional<Map<K, V>> optionalValue = getOptionalValue(name, newMapConverter(keyConverter, valueConverter, mapFactory));
        if (optionalValue.isPresent()) {
            return optionalValue;
        }

        Map<String, String> mapKeys = getMapKeys(name);
        if (mapKeys.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(getValues(name, keyConverter, valueConverter, mapFactory));
    }

    public <K, V, C extends Collection<V>> Optional<Map<K, C>> getOptionalValues(
            String name,
            Class<K> keyClass,
            Class<V> valueClass,
            IntFunction<C> collectionFactory) {
        return getOptionalValues(name, requireConverter(keyClass), requireConverter(valueClass), HashMap::new,
                collectionFactory);
    }

    public <K, V, C extends Collection<V>> Optional<Map<K, C>> getOptionalValues(
            String name,
            Converter<K> keyConverter,
            Converter<V> valueConverter,
            IntFunction<Map<K, C>> mapFactory,
            IntFunction<C> collectionFactory) {
        Optional<Map<K, C>> optionalValue = getOptionalValue(name,
                newMapConverter(keyConverter, newCollectionConverter(valueConverter, collectionFactory), mapFactory));
        if (optionalValue.isPresent()) {
            return optionalValue;
        }

        Map<String, String> mapKeys = getMapIndexedKeys(name);
        if (mapKeys.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(getValues(name, keyConverter, valueConverter, mapFactory, collectionFactory));
    }

    Map<Class<?>, Map<String, ConfigMappingObject>> getMappings() {
        return mappings;
    }

    public <T> T getConfigMapping(Class<T> type) {
        String prefix;
        if (type.isInterface()) {
            ConfigMapping configMapping = type.getAnnotation(ConfigMapping.class);
            prefix = configMapping != null ? configMapping.prefix() : "";
        } else {
            ConfigProperties configProperties = type.getAnnotation(ConfigProperties.class);
            prefix = configProperties != null ? configProperties.prefix() : "";
        }
        return getConfigMapping(type, prefix);
    }

    public <T> T getConfigMapping(Class<T> type, String prefix) {
        if (prefix == null) {
            return getConfigMapping(type);
        }

        Map<String, ConfigMappingObject> mappingsForType = mappings.get(getConfigMappingClass(type));
        if (mappingsForType == null) {
            throw ConfigMessages.msg.mappingNotFound(type.getName());
        }

        ConfigMappingObject configMappingObject = mappingsForType.get(prefix);
        if (configMappingObject == null) {
            throw ConfigMessages.msg.mappingPrefixNotFound(type.getName(), prefix);
        }

        Object value = configMappingObject;
        if (configMappingObject instanceof ConfigMappingClassMapper) {
            value = ((ConfigMappingClassMapper) configMappingObject).map();
        }

        configValidator.validateMapping(type, prefix, value);

        return type.cast(value);
    }

    /**
     * {@inheritDoc}
     *
     * This implementation caches the list of property names collected when {@link SmallRyeConfig} is built via
     * {@link SmallRyeConfigBuilder#build()}.
     *
     * @return the cached names of all configured keys of the underlying configuration
     * @see SmallRyeConfig#getLatestPropertyNames()
     */
    @Override
    public Iterable<String> getPropertyNames() {
        return configSources.getPropertyNames().get();
    }

    /**
     * Provides a way to retrieve an updated list of all property names. The updated list replaces the cached list
     * returned by {@link SmallRyeConfig#getPropertyNames()}.
     *
     * @return the names of all configured keys of the underlying configuration
     */
    @Experimental("Retrieve an updated list of all configuration property names")
    public Iterable<String> getLatestPropertyNames() {
        return configSources.getPropertyNames().get(true);
    }

    /**
     * Checks if a property is present in the {@link Config} instance.
     * <br>
     * Because {@link ConfigSource#getPropertyNames()} may not include all available properties, it is not possible to
     * reliably determine if the property is present in the properties list. The property needs to be retrieved to make
     * sure it exists. The lookup is done without expression expansion, because the expansion value may not be
     * available, and it is not relevant for the final check.
     *
     * @param name the property name.
     * @return true if the property is present or false otherwise.
     */
    @Experimental("Check if a property is present")
    public boolean isPropertyPresent(String name) {
        return Expressions.withoutExpansion(() -> {
            ConfigValue configValue = SmallRyeConfig.this.getConfigValue(name);
            return configValue.getValue() != null && !configValue.getValue().isEmpty();
        });
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return configSources.getSources();
    }

    public Iterable<ConfigSource> getConfigSources(final Class<?> type) {
        final List<ConfigSource> configSourcesByType = new ArrayList<>();
        for (ConfigSource configSource : getConfigSources()) {
            if (type.isAssignableFrom(configSource.getClass())) {
                configSourcesByType.add(configSource);
            }
        }
        return configSourcesByType;
    }

    public Optional<ConfigSource> getConfigSource(final String name) {
        for (ConfigSource configSource : getConfigSources()) {
            final String configSourceName = configSource.getName();
            if (configSourceName != null && configSourceName.equals(name)) {
                return Optional.of(configSource);
            }
        }
        return Optional.empty();
    }

    public <T> T convert(String value, Class<T> asType) {
        return value != null ? requireConverter(asType).convert(value) : null;
    }

    private <T> Converter<Optional<T>> getOptionalConverter(Class<T> asType) {
        Converter<Optional<T>> converter = recast(optionalConverters.get(asType));
        if (converter == null) {
            converter = newOptionalConverter(requireConverter(asType));
            Converter<Optional<T>> appearing = recast(optionalConverters.putIfAbsent(asType, recast(converter)));
            if (appearing != null) {
                converter = appearing;
            }
        }
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static <T> T recast(Object obj) {
        return (T) obj;
    }

    @Deprecated // binary-compatibility bridge method for Quarkus
    public <T> Converter<T> getConverter$$bridge(Class<T> asType) {
        return requireConverter(asType);
    }

    // @Override // in MP Config 2.0+
    public <T> Optional<Converter<T>> getConverter(Class<T> asType) {
        return Optional.ofNullable(getConverterOrNull(asType));
    }

    public <T> Converter<T> requireConverter(final Class<T> asType) {
        final Converter<T> conv = getConverterOrNull(asType);
        if (conv == null) {
            throw ConfigMessages.msg.noRegisteredConverter(asType);
        }
        return conv;
    }

    @SuppressWarnings("unchecked")
    <T> Converter<T> getConverterOrNull(Class<T> asType) {
        final Converter<?> exactConverter = converters.get(asType);
        if (exactConverter != null) {
            return (Converter<T>) exactConverter;
        }
        if (asType.isPrimitive()) {
            return (Converter<T>) getConverterOrNull(Converters.wrapPrimitiveType(asType));
        }
        if (asType.isArray()) {
            final Converter<?> conv = getConverterOrNull(asType.getComponentType());
            return conv == null ? null : Converters.newArrayConverter(conv, asType);
        }
        return (Converter<T>) converters.computeIfAbsent(asType, clazz -> ImplicitConverters.getConverter((Class<?>) clazz));
    }

    @Override
    public <T> T unwrap(final Class<T> type) {
        if (Config.class.isAssignableFrom(type)) {
            return type.cast(this);
        }

        throw ConfigMessages.msg.getTypeNotSupportedForUnwrapping(type);
    }

    public List<String> getProfiles() {
        return configSources.getProfiles();
    }

    public ConfigSource getDefaultValues() {
        return configSources.defaultValues;
    }

    ConfigSourceInterceptorContext interceptorChain() {
        return configSources.interceptorChain;
    }

    private static class ConfigSources implements Serializable {
        private static final long serialVersionUID = 3483018375584151712L;

        private final List<String> profiles;
        private final List<ConfigSource> sources;
        private final ConfigSource defaultValues;
        private final ConfigSourceInterceptorContext interceptorChain;
        private final PropertyNames propertyNames;

        /**
         * Builds a representation of Config Sources, Interceptors and the Interceptor chain to be used in Config. Note
         * that this constructor must be used when the Config object is being initialized, because interceptors also
         * require initialization.
         */
        ConfigSources(final SmallRyeConfigBuilder builder, final SmallRyeConfig config) {
            // Add all sources except for ConfigurableConfigSource types. These are initialized later
            List<ConfigSource> sources = buildSources(builder);
            // Add the default values sources separately, so we can keep a reference to it and add mappings defaults
            DefaultValuesConfigSource defaultValues = new DefaultValuesConfigSource(builder.getDefaultValues());
            sources.add(defaultValues);

            // Add all interceptors
            List<ConfigSourceInterceptor> interceptors = new ArrayList<>();
            List<InterceptorWithPriority> interceptorWithPriorities = buildInterceptors(builder);

            // Create the initial chain with initial sources and all interceptors
            SmallRyeConfigSourceInterceptorContext current = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, config);
            current = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(mapSources(sources)), current,
                    config);
            for (InterceptorWithPriority interceptorWithPriority : interceptorWithPriorities) {
                ConfigSourceInterceptor interceptor = interceptorWithPriority.getInterceptor(current);
                interceptors.add(interceptor);
                current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, config);
            }

            // Init all late sources
            List<String> profiles = getProfiles(interceptors);
            List<ConfigSourceWithPriority> sourcesWithPriorities = mapLateSources(sources, interceptors, current, profiles,
                    builder, config);
            List<ConfigSource> configSources = getSources(sourcesWithPriorities);

            // Rebuild the chain with the late sources and new instances of the interceptors
            // The new instance will ensure that we get rid of references to factories and other stuff and keep only
            // the resolved final source or interceptor to use.
            current = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, config);
            current = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(sourcesWithPriorities), current,
                    config);
            for (ConfigSourceInterceptor interceptor : interceptors) {
                current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, config);
            }

            this.profiles = profiles;
            this.sources = configSources;
            this.defaultValues = defaultValues;
            this.interceptorChain = current;
            this.propertyNames = new PropertyNames();
        }

        private static List<ConfigSource> buildSources(final SmallRyeConfigBuilder builder) {
            List<ConfigSource> sourcesToBuild = new ArrayList<>(builder.getSources());
            for (ConfigSourceProvider sourceProvider : builder.getSourceProviders()) {
                for (ConfigSource configSource : sourceProvider.getConfigSources(builder.getClassLoader())) {
                    sourcesToBuild.add(configSource);
                }
            }

            if (builder.isAddDiscoveredSources()) {
                sourcesToBuild.addAll(builder.discoverSources());
            }
            if (builder.isAddDefaultSources()) {
                sourcesToBuild.addAll(builder.getDefaultSources());
            }

            return sourcesToBuild;
        }

        private static List<InterceptorWithPriority> buildInterceptors(final SmallRyeConfigBuilder builder) {
            List<InterceptorWithPriority> interceptors = new ArrayList<>(builder.getInterceptors());
            if (builder.isAddDiscoveredInterceptors()) {
                interceptors.addAll(builder.discoverInterceptors());
            }
            if (builder.isAddDefaultInterceptors()) {
                interceptors.addAll(builder.getDefaultInterceptors());
            }

            interceptors.sort(null);
            return interceptors;
        }

        private static List<ConfigSourceWithPriority> mapSources(final List<ConfigSource> sources) {
            List<ConfigSourceWithPriority> sourcesWithPriority = new ArrayList<>();
            for (ConfigSource source : sources) {
                if (!(source instanceof ConfigurableConfigSource)) {
                    sourcesWithPriority.add(new ConfigSourceWithPriority(source));
                }
            }
            sourcesWithPriority.sort(null);
            Collections.reverse(sourcesWithPriority);
            return sourcesWithPriority;
        }

        private static List<String> getProfiles(final List<ConfigSourceInterceptor> interceptors) {
            for (ConfigSourceInterceptor interceptor : interceptors) {
                if (interceptor instanceof ProfileConfigSourceInterceptor) {
                    return Arrays.asList(((ProfileConfigSourceInterceptor) interceptor).getProfiles());
                }
            }
            return Collections.emptyList();
        }

        private static List<ConfigSourceWithPriority> mapLateSources(
                final List<ConfigSource> sources,
                final List<ConfigSourceInterceptor> interceptors,
                final ConfigSourceInterceptorContext current,
                final List<String> profiles,
                final SmallRyeConfigBuilder builder,
                final SmallRyeConfig config) {

            ConfigSourceWithPriority.resetLoadPriority();
            List<ConfigSourceWithPriority> currentSources = new ArrayList<>();

            // Init all profile sources first
            List<ConfigSource> profileSources = new ArrayList<>();
            ConfigSourceContext mainContext = new SmallRyeConfigSourceContext(current, profiles, sources);
            for (ConfigurableConfigSource profileSource : getConfigurableSources(sources)) {
                if (profileSource.getFactory() instanceof ProfileConfigSourceFactory) {
                    profileSources.addAll(profileSource.getConfigSources(mainContext));
                }
            }

            // Sort the profiles sources with the main sources
            currentSources.addAll(mapSources(profileSources));
            currentSources.addAll(mapSources(sources));
            currentSources.sort(null);
            Collections.reverse(currentSources);

            // Rebuild the chain with the profiles sources, so profiles values are also available in factories
            ConfigSourceInterceptorContext context = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, config);
            context = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(currentSources), context, config);
            for (ConfigSourceInterceptor interceptor : interceptors) {
                context = new SmallRyeConfigSourceInterceptorContext(interceptor, context, config);
            }

            // Init remaining sources, coming from SmallRyeConfig
            int countSourcesFromLocations = 0;
            List<ConfigSource> lateSources = new ArrayList<>();
            ConfigSourceContext profileContext = new SmallRyeConfigSourceContext(context, profiles,
                    currentSources.stream().map(ConfigSourceWithPriority::getSource).collect(toList()));
            for (ConfigurableConfigSource lateSource : getConfigurableSources(sources)) {
                if (!(lateSource.getFactory() instanceof ProfileConfigSourceFactory)) {
                    List<ConfigSource> configSources = lateSource.getConfigSources(profileContext);

                    if (lateSource.getFactory() instanceof AbstractLocationConfigSourceFactory) {
                        countSourcesFromLocations = countSourcesFromLocations + configSources.size();
                    }

                    lateSources.addAll(configSources);
                }
            }

            if (countSourcesFromLocations == 0 && builder.isAddDiscoveredSources()) {
                ConfigValue locations = profileContext.getValue(SMALLRYE_CONFIG_LOCATIONS);
                if (locations != null && locations.getValue() != null) {
                    ConfigLogging.log.configLocationsNotFound(SMALLRYE_CONFIG_LOCATIONS, locations.getValue());
                }
            }

            // Sort the final sources
            currentSources.clear();
            currentSources.addAll(mapSources(lateSources));
            currentSources.addAll(mapSources(profileSources));
            currentSources.addAll(mapSources(sources));
            currentSources.sort(null);
            Collections.reverse(currentSources);

            return currentSources;
        }

        private static List<ConfigSource> getSources(final List<ConfigSourceWithPriority> sourceWithPriorities) {
            List<ConfigSource> configSources = new ArrayList<>();
            for (ConfigSourceWithPriority configSourceWithPriority : sourceWithPriorities) {
                ConfigSource source = configSourceWithPriority.getSource();
                configSources.add(source);
                if (ConfigLogging.log.isDebugEnabled()) {
                    ConfigLogging.log.loadedConfigSource(source.getName(), source.getOrdinal());
                }
            }
            return Collections.unmodifiableList(configSources);
        }

        private static List<ConfigurableConfigSource> getConfigurableSources(final List<ConfigSource> sources) {
            List<ConfigurableConfigSource> configurableConfigSources = new ArrayList<>();
            for (ConfigSource source : sources) {
                if (source instanceof ConfigurableConfigSource) {
                    configurableConfigSources.add((ConfigurableConfigSource) source);
                }
            }
            configurableConfigSources.sort(Comparator.comparingInt(ConfigurableConfigSource::getOrdinal).reversed());
            return Collections.unmodifiableList(configurableConfigSources);
        }

        List<String> getProfiles() {
            return profiles;
        }

        List<ConfigSource> getSources() {
            return sources;
        }

        ConfigSourceInterceptorContext getInterceptorChain() {
            return interceptorChain;
        }

        PropertyNames getPropertyNames() {
            return propertyNames;
        }

        class PropertyNames implements Serializable {
            private static final long serialVersionUID = 4193517748286869745L;

            private final Set<String> names = new HashSet<>();

            Iterable<String> get() {
                if (names.isEmpty()) {
                    return get(true);
                }
                return names;
            }

            Iterable<String> get(boolean latest) {
                if (latest) {
                    names.clear();
                    Iterator<String> namesIterator = interceptorChain.iterateNames();
                    while (namesIterator.hasNext()) {
                        names.add(namesIterator.next());
                    }
                }
                return Collections.unmodifiableSet(names);
            }
        }
    }

    static class ConfigSourceWithPriority implements Comparable<ConfigSourceWithPriority>, Serializable {
        private static final long serialVersionUID = 3709554647398262957L;

        private final ConfigSource source;
        private final int priority;
        private final int loadPriority = loadPrioritySequence++;

        ConfigSourceWithPriority(final ConfigSource source) {
            this.source = source;
            this.priority = source.getOrdinal();
        }

        ConfigSource getSource() {
            return source;
        }

        @Override
        public int compareTo(final ConfigSourceWithPriority other) {
            int res = Integer.compare(this.priority, other.priority);
            return res != 0 ? res : Integer.compare(other.loadPriority, this.loadPriority);
        }

        private static int loadPrioritySequence = 0;

        static void resetLoadPriority() {
            loadPrioritySequence = 0;
        }
    }

    private static class SmallRyeConfigSourceContext implements ConfigSourceContext {
        private final ConfigSourceInterceptorContext context;
        private final List<String> profiles;
        private final List<ConfigSource> sources;

        public SmallRyeConfigSourceContext(
                final ConfigSourceInterceptorContext context,
                final List<String> profiles,
                final List<ConfigSource> sources) {
            this.context = context;
            this.profiles = profiles;
            this.sources = sources;
        }

        @Override
        public ConfigValue getValue(final String name) {
            ConfigValue value = context.proceed(name);
            return value != null ? value : ConfigValue.builder().withName(name).build();
        }

        @Override
        public List<String> getProfiles() {
            return profiles;
        }

        @Override
        public List<ConfigSource> getConfigSources() {
            return sources;
        }

        @Override
        public Iterator<String> iterateNames() {
            return context.iterateNames();
        }
    }

    private Object writeReplace() throws ObjectStreamException {
        return RegisteredConfig.instance;
    }

    /**
     * Serialization placeholder which deserializes to the current registered config
     */
    private static class RegisteredConfig implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final RegisteredConfig instance = new RegisteredConfig();

        private Object readResolve() throws ObjectStreamException {
            return ConfigProvider.getConfig();
        }
    }
}
