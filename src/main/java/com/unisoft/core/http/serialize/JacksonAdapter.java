package com.unisoft.core.http.serialize;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unisoft.core.http.HttpHeader;
import com.unisoft.core.http.HttpHeaders;
import com.unisoft.core.http.annotation.HeaderCollection;
import com.unisoft.core.http.serialize.impl.MalformedValueException;
import com.unisoft.core.util.AccessibleByteArrayOutputStream;
import com.unisoft.core.util.CoreUtil;
import com.unisoft.core.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.regex.Pattern;

/**
 * @author omar.H.Ajmi
 * @since 18/10/2020
 */
public class JacksonAdapter implements SerializerAdapter {
    private static final Logger log = LoggerFactory.getLogger(JacksonAdapter.class);

    private static final Pattern PATTERN = Pattern.compile("^\"*|\"*$");
    /*
     * The lazily-created serializer for this ServiceClient.
     */
    private static SerializerAdapter serializerAdapter;
    /**
     * An instance of {@link ObjectMapper} to serialize/deserialize objects.
     */
    private final ObjectMapper mapper;
    /**
     * An instance of {@link ObjectMapper} that does not do flattening.
     */
    private final ObjectMapper simpleMapper;
    private final ObjectMapper xmlMapper;
    private final ObjectMapper headerMapper;

    /**
     * Creates a new JacksonAdapter instance with default mapper settings.
     */
    public JacksonAdapter() {
        simpleMapper = initializeObjectMapper(new ObjectMapper());

        xmlMapper = initializeObjectMapper(new XmlMapper())
                .setDefaultUseWrapper(false)
                .configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);

        ObjectMapper flatteningMapper = initializeObjectMapper(new ObjectMapper())
                .registerModule(FlatteningDeserializer.getModule(simpleMapper()))
                .registerModule(FlatteningDeserializer.getModule(simpleMapper()));

        mapper = initializeObjectMapper(new ObjectMapper())
                // Order matters: must register in reverse order of hierarchy
                .registerModule(AdditionalPropertiesSerializer.getModule(flatteningMapper))
                .registerModule(AdditionalPropertiesDeserializer.getModule(flatteningMapper))
                .registerModule(FlatteningSerializer.getModule(simpleMapper()))
                .registerModule(FlatteningDeserializer.getModule(simpleMapper()));

        headerMapper = simpleMapper
                .copy()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    }

    /**
     * maintain singleton instance of the default serializer adapter.
     *
     * @return the default serializer
     */
    public static synchronized SerializerAdapter createDefaultSerializerAdapter() {
        if (serializerAdapter == null) {
            serializerAdapter = new JacksonAdapter();
        }
        return serializerAdapter;
    }

    /**
     * Initializes an instance of JacksonMapperAdapter with default configurations applied to the object mapper.
     *
     * @param mapper the object mapper to use.
     */
    private static <T extends ObjectMapper> T initializeObjectMapper(T mapper) {
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .registerModule(new JavaTimeModule())
                .registerModule(ByteArraySerializer.getModule())
                .registerModule(Base64UrlSerializer.getModule())
                .registerModule(DateTimeSerializer.getModule())
                .registerModule(DateTimeDeserializer.getModule())
                .registerModule(DateTimeRfc1123Serializer.getModule())
                .registerModule(DurationSerializer.getModule())
                .registerModule(HttpHeadersSerializer.getModule())
                .registerModule(UnixTimeSerializer.getModule());
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
        return mapper;
    }

    /**
     * Gets a static instance of {@link ObjectMapper} that doesn't handle flattening.
     *
     * @return an instance of {@link ObjectMapper}.
     */
    protected ObjectMapper simpleMapper() {
        return simpleMapper;
    }

    /**
     * @return the original serializer type
     */
    public ObjectMapper serializer() {
        return mapper;
    }

    @Override
    public String serialize(Object object, SerializerEncoding encoding) throws IOException {
        if (object == null) {
            return null;
        }

        ByteArrayOutputStream stream = new AccessibleByteArrayOutputStream();
        serialize(object, encoding, stream);

        return new String(stream.toByteArray(), 0, stream.size(), StandardCharsets.UTF_8);
    }

    @Override
    public void serialize(Object object, SerializerEncoding encoding, OutputStream outputStream) throws IOException {
        if (object == null) {
            return;
        }

        if ((encoding == SerializerEncoding.XML)) {
            xmlMapper.writeValue(outputStream, object);
        } else {
            serializer().writeValue(outputStream, object);
        }
    }

    @Override
    public String serializeRaw(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return PATTERN.matcher(serialize(object, SerializerEncoding.JSON)).replaceAll("");
        } catch (IOException ex) {
            log.warn("Failed to serialize {} to JSON.", object.getClass(), ex);
            return null;
        }
    }

    @Override
    public String serializeList(List<?> list, CollectionFormat format) {
        if (list == null) {
            return null;
        }
        List<String> serialized = new ArrayList<>();
        for (Object element : list) {
            String raw = serializeRaw(element);
            serialized.add(raw != null ? raw : "");
        }
        return String.join(format.getDelimiter(), serialized);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(String value, Type type, SerializerEncoding encoding) throws IOException {
        if (CoreUtil.isNullOrEmpty(value)) {
            return null;
        }

        final JavaType javaType = createJavaType(type);
        try {
            if (encoding == SerializerEncoding.XML) {
                return xmlMapper.readValue(value, javaType);
            } else {
                return serializer().readValue(value, javaType);
            }
        } catch (JsonParseException jpe) {
            throw new MalformedValueException(jpe.getMessage(), jpe);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(InputStream inputStream, final Type type, SerializerEncoding encoding)
            throws IOException {
        if (inputStream == null) {
            return null;
        }

        final JavaType javaType = createJavaType(type);
        try {
            if (encoding == SerializerEncoding.XML) {
                return xmlMapper.readValue(inputStream, javaType);
            } else {
                return serializer().readValue(inputStream, javaType);
            }
        } catch (JsonParseException jpe) {
            throw new MalformedValueException(jpe.getMessage(), jpe);
        }
    }

    @Override
    public <T> T deserialize(HttpHeaders headers, Type deserializedHeadersType) throws IOException {
        if (deserializedHeadersType == null) {
            return null;
        }

        final String headersJsonString = headerMapper.writeValueAsString(headers);
        T deserializedHeaders =
                headerMapper.readValue(headersJsonString, createJavaType(deserializedHeadersType));

        final Class<?> deserializedHeadersClass = TypeUtil.getRawClass(deserializedHeadersType);
        final Field[] declaredFields = deserializedHeadersClass.getDeclaredFields();
        for (final Field declaredField : declaredFields) {
            if (!declaredField.isAnnotationPresent(HeaderCollection.class)) {
                continue;
            }

            final Type declaredFieldType = declaredField.getGenericType();
            if (!TypeUtil.isTypeOrSubTypeOf(declaredField.getType(), Map.class)) {
                continue;
            }

            final Type[] mapTypeArguments = TypeUtil.getTypeArguments(declaredFieldType);
            if (mapTypeArguments.length == 2
                    && mapTypeArguments[0] == String.class
                    && mapTypeArguments[1] == String.class) {
                final HeaderCollection headerCollectionAnnotation = declaredField.getAnnotation(HeaderCollection.class);
                final String headerCollectionPrefix = headerCollectionAnnotation.value().toLowerCase(Locale.ROOT);
                final int headerCollectionPrefixLength = headerCollectionPrefix.length();
                if (headerCollectionPrefixLength > 0) {
                    final Map<String, String> headerCollection = new HashMap<>();
                    for (final HttpHeader header : headers) {
                        final String headerName = header.getName();
                        if (headerName.toLowerCase(Locale.ROOT).startsWith(headerCollectionPrefix)) {
                            headerCollection.put(headerName.substring(headerCollectionPrefixLength),
                                    header.getValue());
                        }
                    }

                    final boolean declaredFieldAccessibleBackup = declaredField.isAccessible();
                    try {
                        if (!declaredFieldAccessibleBackup) {
                            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                                declaredField.setAccessible(true);
                                return null;
                            });
                        }
                        declaredField.set(deserializedHeaders, headerCollection);
                    } catch (IllegalAccessException ignored) {
                    } finally {
                        if (!declaredFieldAccessibleBackup) {
                            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                                declaredField.setAccessible(false);
                                return null;
                            });
                        }
                    }
                }
            }
        }
        return deserializedHeaders;
    }

    private JavaType createJavaType(Type type) {
        JavaType result;
        if (type == null) {
            result = null;
        } else if (type instanceof JavaType) {
            result = (JavaType) type;
        } else if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            JavaType[] javaTypeArguments = new JavaType[actualTypeArguments.length];
            for (int i = 0; i != actualTypeArguments.length; i++) {
                javaTypeArguments[i] = createJavaType(actualTypeArguments[i]);
            }
            result = mapper
                    .getTypeFactory().constructParametricType((Class<?>) parameterizedType.getRawType(), javaTypeArguments);
        } else {
            result = mapper
                    .getTypeFactory().constructType(type);
        }
        return result;
    }
}
