package org.springframework.data.marklogic.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.marklogic.xcc.*;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.types.XName;
import com.marklogic.xcc.types.XdmNode;
import com.marklogic.xcc.types.XdmValue;
import com.marklogic.xcc.types.XdmVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.ConvertingPropertyAccessor;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.marklogic.core.convert.MappingMarklogicConverter;
import org.springframework.data.marklogic.core.convert.MarklogicContentHolder;
import org.springframework.data.marklogic.core.convert.MarklogicConverter;
import org.springframework.data.marklogic.core.convert.MarklogicWriter;
import org.springframework.data.marklogic.core.mapping.*;
import org.springframework.data.marklogic.core.mapping.event.AfterSaveEvent;
import org.springframework.data.marklogic.core.mapping.event.BeforeConvertEvent;
import org.springframework.data.marklogic.core.mapping.event.BeforeSaveEvent;
import org.springframework.data.marklogic.core.mapping.event.MarklogicMappingEvent;
import org.springframework.data.marklogic.datasource.ContentSourceUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Primary implementation of {@link MarklogicOperations}.
 *
 * @author Stéphane Toussaint
 */
public class MarklogicTemplate implements MarklogicOperations, ApplicationEventPublisherAware {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MarklogicTemplate.class);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final static Class<?>[] SUPPORTED_CONTENT_CLASS = {
            Document.class,
            Node.class,
            JsonNode.class,
            XdmNode.class,
            File.class,
            RandomAccessFile.class,
            URL.class,
            URI.class,
            String.class,
            byte[].class,
            InputStream.class
    };

    private final ContentSource contentSource;
    private final MarklogicConverter marklogicConverter;
    private final MappingContext<? extends MarklogicPersistentEntity<?>, MarklogicPersistentProperty> mappingContext;

    private ApplicationEventPublisher eventPublisher;

    public MarklogicTemplate(ContentSource contentSource) {
        this(contentSource, null);
    }

    public MarklogicTemplate(ContentSource contentSource, MarklogicConverter marklogicConverter) {
        Assert.notNull(contentSource);

        this.contentSource = contentSource;
        this.marklogicConverter = marklogicConverter == null ? getDefaultMarklogicConverter() : marklogicConverter;
        this.mappingContext = this.marklogicConverter.getMappingContext();
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    @Override
    public void insert(Object objectToSave) {
        final String uri = mappingContext.getPersistentEntity(objectToSave.getClass()).getUri();
        final String defaultCollection = mappingContext.getPersistentEntity(objectToSave.getClass()).getDefaultCollection();

        insert(objectToSave, new MarklogicCreateOperationOptions() {
            @Override
            public String uri() {
                return uri;
            }

            @Override
            public String[] extraCollections() {
                return null;
            }

            @Override
            public String defaultCollection() {
                return defaultCollection;
            }
        });
    }

    @Override
    public void insert(Object objectToSave, MarklogicCreateOperationOptions options) {
        assertAutoGenerableIdIfNotSet(objectToSave);

        generateIdIfNecessary(objectToSave);

        doInsert(computeUri(options.uri(), objectToSave, retrieveIdentifier(objectToSave)), objectToSave, options, this.marklogicConverter);
    }

    @Override
    public void save(Object objectToSave) {
        if (!checkIdIsSet(objectToSave)) {
            LOGGER.debug("Save operation issued with unidentified object. Fallback to insert operation.");
            insert(objectToSave);
        } else {
            final String uri = retrieveUri(objectToSave);
            final String defaultCollection = marklogicConverter.computeDefaultCollection(objectToSave);

            save(objectToSave, new MarklogicCreateOperationOptions() {
                @Override
                public String uri() {
                    return uri;
                }

                @Override
                public String[] extraCollections() {
                    return null;
                }

                @Override
                public String defaultCollection() {
                    return defaultCollection;
                }
            });
        }
    }

    @Override
    public void save(Object objectToSave, MarklogicCreateOperationOptions options) {
        doInsert(computeUri(options.uri(), objectToSave, retrieveIdentifier(objectToSave)), objectToSave, options, marklogicConverter);
    }

    @Override
    public void remove(Object entity) {
        String uri = retrieveUri(entity);
        if (uri != null) {
            LOGGER.debug("Remove '{}' from '{}'", entity, uri);
            invokeAdhocQuery("xdmp:document-delete('" + uri + "')", new MarklogicInvokeOperationOptions() {
                @Override
                public boolean useCacheResult() {
                    return false;
                }
            });
        }
    }

    @Override
    public <T> void remove(Object id, Class<T> entityClass) {
        T entity = findById(id, entityClass);
        remove(entity);
    }

    @Override
    public <T> void remove(Object id, Class<T> entityClass, MarklogicOperationOptions options) {
        T entity = findById(id, entityClass, options);
        remove(entity);
    }

    @Override
    public <T> void removeAll(Class<T> entityClass) {
        String collection = determineCollectionName(entityClass);
        removeAll(entityClass, new MarklogicOperationOptions() {
            @Override
            public String defaultCollection() {
                return collection;
            }
        });
    }

    @Override
    public <T> void removeAll(Class<T> entityClass, MarklogicOperationOptions options) {
        Assert.notNull(options.defaultCollection(), "A collection should be provided for removeAll operation!");

        doRemoveAll(options.defaultCollection());
    }

    @Override
    public <T> T findById(Object id, Class<T> entityClass) {
        return findById(id, entityClass, new MarklogicOperationOptions() {
            @Override
            public String defaultCollection() {
                return determineCollectionName(entityClass);
            }
        });
    }

    @Override
    public <T> T findById(Object id, Class<T> entityClass, MarklogicOperationOptions options) {
        MarklogicIdentifier identifier = resolveMarklogicIdentifier(id, entityClass);
        final String targetCollection = retrieveTargetCollection(computeDefaultCollection(options.defaultCollection(), null, id));
        final boolean isIdInPropertyFragment = options.idInPropertyFragment();

        LOGGER.debug("Retrieve object stored in '{}' default collection with '{}' as identifier", targetCollection, id);

        StringBuilder sb = new StringBuilder("cts:search(" + targetCollection + ",");
        if (isIdInPropertyFragment) {
            sb.append("cts:properties-fragment-query(");
        }
        sb.append("cts:element-value-query(fn:QName(\"" + identifier.qname().getNamespaceURI() + "\", \"" + identifier.qname().getLocalPart() + "\"), \"" + identifier.value() + "\")");
        if (isIdInPropertyFragment) {
            sb.append(")");
        }
        sb.append(")");

        LOGGER.trace(sb.toString());

        return invokeAdhocQuery(sb.toString(), entityClass, new MarklogicInvokeOperationOptions() {});
    }

    @Override
    public <T> List<T> find(Object query, Class<T> entityClass) {
        return find(query, entityClass, new MarklogicOperationOptions() {});
    }

    @Override
    public <T> List<T> find(Object query, Class<T> entityClass, MarklogicOperationOptions options) {
        final String targetCollection = retrieveTargetCollection(options.defaultCollection());

        LOGGER.debug("Retrieve objects matching query '{}' for collection '{}'", "empty", targetCollection);

        return invokeAdhocQueryAsList("cts:search(" + targetCollection + ", cts:and-query(()));", entityClass, new MarklogicInvokeOperationOptions() {
            @Override
            public boolean useCacheResult() {
                return false;
            }
        });
    }

    @Override
    public <T> T findOne(Object query, Class<T> entityClass) {
        return findOne(query, entityClass, new MarklogicOperationOptions() {});
    }

    @Override
    public <T> T findOne(Object query, Class<T> entityClass, MarklogicOperationOptions options) {
        List<T> resultList = find(query, entityClass, new MarklogicOperationOptions() {});

        if (CollectionUtils.isEmpty(resultList)) {
            return null;
        } else if (resultList.size() == 1) {
            return resultList.get(0);
        } else {
            throw new DataRetrievalFailureException("Only one result expected. You should probably call find instead");
        }
    }

    @Override
    public <T> List<T> invokeModuleAsList(String moduleName, Class<T> resultClass, MarklogicInvokeOperationOptions options) {
        return returnInSession(session -> {
            try {
                ResultSequence resultSequence = session.submitRequest(buildModuleRequest(moduleName, options, session));
                return prepareResultList(resultSequence, resultClass);
            } catch (RequestException re) {
                throw new DataRetrievalFailureException("Unable to submit request", re);
            }
        });
    }

    @Override
    public <T> T invokeModule(String moduleName, Class<T> resultClass, MarklogicInvokeOperationOptions options) {
        List<T> resultList = invokeModuleAsList(moduleName, resultClass, options);

        if (CollectionUtils.isEmpty(resultList)) {
            return null;
        } else if (resultList.size() == 1) {
            return resultList.get(0);
        } else {
            throw new DataRetrievalFailureException("Only one result expected. You should probably call invokeModuleAsList instead");
        }
    }

    @Override
    public void invokeModule(String moduleName, MarklogicInvokeOperationOptions options) {
        doInSession(session -> {
            try {
                session.submitRequest(buildModuleRequest(moduleName, options, session));
            } catch (RequestException re) {
                throw new DataRetrievalFailureException("Unable to submit request", re);
            }
        });
    }

    @Override
    public <T> List<T> invokeAdhocQueryAsList(String query, Class<T> resultClass, MarklogicInvokeOperationOptions options) {
        return returnInSession(session -> {
            try {
                ResultSequence resultSequence = session.submitRequest(buildAdhocRequest(query, options, session));
                return prepareResultList(resultSequence, resultClass);
            } catch (RequestException re) {
                throw new DataRetrievalFailureException("Unable to submit request", re);
            }
        });
    }

    @Override
    public <T> T invokeAdhocQuery(String query, Class<T> resultClass, MarklogicInvokeOperationOptions options) {
        List<T> resultList = invokeAdhocQueryAsList(query, resultClass, options);

        if (CollectionUtils.isEmpty(resultList)) {
            return null;
        } else if (resultList.size() == 1) {
            return resultList.get(0);
        } else {
            throw new DataRetrievalFailureException("Only one result expected. You should probably call invokeAdhocQueryAsList instead");
        }
    }

    @Override
    public void invokeAdhocQuery(String query, MarklogicInvokeOperationOptions options) {
        doInSession(session -> {
            try {
                session.submitRequest(buildAdhocRequest(query, options, session));
            } catch (RequestException re) {
                throw new DataRetrievalFailureException("Unable to submit request", re);
            }
        });
    }

    @Override
    public MarklogicConverter getConverter() {
        return this.marklogicConverter;
    }

    /*
        #############################
        ## PRIVATE IMPLEMENTATIONS ##
        #############################
        */
    private void doRemoveAll(String collection) {
        LOGGER.debug("Remove all entities stored in '{}' default collection", collection);

        String query = "xdmp:collection-delete(\"" + collection + "\")";

        doInSession(session -> {
            Request request = session.newAdhocQuery(query);
            try {
                session.submitRequest(request);
            } catch (RequestException re) {
                throw new DataRetrievalFailureException("Unable to query uri", re);
            }
        });
    }

    private <T> void doInsert(String uri, T objectToSave, MarklogicCreateOperationOptions options, MarklogicWriter<T> writer) {
        LOGGER.debug("Insert entity '{}' at '{}' within '{}' default collection", objectToSave, options.uri(), options.defaultCollection());

        maybeEmitEvent(new BeforeConvertEvent<>(objectToSave, uri));

        Content content = toContentObject(uri, objectToSave, options, writer);

        maybeEmitEvent(new BeforeSaveEvent<>(objectToSave, content, uri));

        doInsertContent(content);

        maybeEmitEvent(new AfterSaveEvent<>(objectToSave, content, uri));
    }

    private <T> void maybeEmitEvent(MarklogicMappingEvent<T> event) {
        if (null != eventPublisher) {
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * Populates the id property of the saved object, if it's not set already.
     *
     * @param objectToSave
     */
    private void generateIdIfNecessary(Object objectToSave) {

        MarklogicPersistentProperty property = getIdPropertyFor(objectToSave.getClass());

        if (property == null) {
            return;
        }

        MarklogicPersistentEntity<?> entity = mappingContext.getPersistentEntity(objectToSave.getClass());
        PersistentPropertyAccessor accessor = entity.getPropertyAccessor(objectToSave);

        if (accessor.getProperty(property) != null) {
            return;
        }

        ConversionService conversionService = marklogicConverter.getConversionService();
        new ConvertingPropertyAccessor(accessor, conversionService).setProperty(property, UUID.randomUUID());
    }

    private boolean checkIdIsSet(Object objectToSave) {
        MarklogicPersistentProperty property = getIdPropertyFor(objectToSave.getClass());

        if (property == null) {
            return false;
        }

        MarklogicPersistentEntity<?> entity = mappingContext.getPersistentEntity(objectToSave.getClass());
        return entity.getPropertyAccessor(objectToSave).getProperty(property) != null;
    }

    private String retrieveUri(Object objectToSave) {
        final MarklogicIdentifier identifier = resolveMarklogicIdentifier(objectToSave);
        String collection = marklogicConverter.computeDefaultCollection(objectToSave);

        String collectionConstraints = null;
        if (collection != null) {
            collectionConstraints = "cts:collection-query(\"" + collection + "\")";
        }

        final boolean isIdInPropertyFragment = false; // TODO : Somethig wrong here, I need to retrieve the information

        LOGGER.debug("Looking to uri for object stored in '{}' default collection with '{}' as identifier", collection, identifier.qname());

        StringBuilder sb = new StringBuilder("cts:uris((), (), cts:and-query((");
        if (collectionConstraints != null) {
            sb.append(collectionConstraints).append(",");
        }

        if (isIdInPropertyFragment) {
            sb.append("cts:properties-fragment-query(");
        }
        sb.append("cts:element-value-query(fn:QName(\"" + identifier.qname().getNamespaceURI() + "\", \"" + identifier.qname().getLocalPart() + "\"), \"" + identifier.value() + "\")");
        if (isIdInPropertyFragment) {
            sb.append(")");
        }
        sb.append(")))");

        LOGGER.trace(sb.toString());

        List<String> uris = invokeAdhocQueryAsList(sb.toString(), String.class, new MarklogicInvokeOperationOptions() {});

        if (! CollectionUtils.isEmpty(uris)) {
            return uris.get(0);
        } else {
            return marklogicConverter.computeUri(objectToSave);
        }
    }

    private String computeUri(String uri, Object entity, Object id) {
        Expression expression = detectExpression(uri);
        return expression == null ? uri : expression.getValue(new DocumentExpressionContext() {
            @Override
            public Object getEntity() {
                return entity;
            }

            @Override
            public Object getId() {
                return id;
            }
        }, String.class);
    }

    private String computeDefaultCollection(String collection, Object entity, Object id) {
        Expression expression = detectExpression(collection);
        return expression == null ? collection : expression.getValue(new DocumentExpressionContext() {
            @Override
            public Object getEntity() {
                return entity;
            }

            @Override
            public Object getId() {
                return id;
            }
        }, String.class);
    }

    private <T> T returnInSession(Function<Session, T> sessionTask) {
        Session session = ContentSourceUtils.getSession(contentSource);
        try {
            return sessionTask.apply(session);
        } finally {
            ContentSourceUtils.releaseSession(session, contentSource);
        }
    }

    private void doInSession(Consumer<Session> sessionTask) {
        Session session = ContentSourceUtils.getSession(contentSource);
        try {
            sessionTask.accept(session);
        } finally {
            ContentSourceUtils.releaseSession(session, contentSource);
        }
    }

    private <T> Content toContentObject(String uri, T objectToSave, MarklogicOperationOptions options, MarklogicWriter<T> writer) {
        Content content;
        boolean supportedClass = Stream.of(SUPPORTED_CONTENT_CLASS).anyMatch(c -> c.isAssignableFrom(objectToSave.getClass()));

        if (! supportedClass) {
            MarklogicContentHolder holder = new MarklogicContentHolder();
            writer.write(objectToSave, holder);
            content = createSupportedContentObject(uri, holder.getContent());
            content.getCreateOptions().setFormatXml(); // Should we really consider that anything here is XML ?
        } else {
            content = createSupportedContentObject(uri, objectToSave);
        }

        if (options != null) {
            content.getCreateOptions().setCollections(Stream.of(options.defaultCollection()).filter(StringUtils::hasText).toArray(String[]::new));
        }

        return content;
    }

    private void doInsertContent(Content content) {
        doInSession((session) -> {
            try {
                session.insertContent(content);
            } catch (RequestException re) {
                throw new DataAccessResourceFailureException("Unable to execute request", re);
            }
        });
    }

    private void assertAutoGenerableIdIfNotSet(Object entity) {

        MarklogicPersistentEntity<?> persistentEntity = mappingContext.getPersistentEntity(entity.getClass());
        MarklogicPersistentProperty idProperty = persistentEntity == null ? null : persistentEntity.getIdProperty();

        if (idProperty == null) {
            return;
        }

        Object idValue = persistentEntity.getPropertyAccessor(entity).getProperty(idProperty);

        if (idValue == null && !MarklogicSimpleTypes.AUTOGENERATED_ID_TYPES.contains(idProperty.getType())) {
            throw new InvalidDataAccessApiUsageException(
                    String.format("Cannot auto-generate id of type %s for entity of type %s!", idProperty.getType().getName(),
                            entity.getClass().getName()));
        }
    }

    private Content createSupportedContentObject(String uri, Object objectToSave) {
        try {
            if (objectToSave instanceof Document) {
                return ContentFactory.newContent(uri, (Document) objectToSave, ContentCreateOptions.newXmlInstance());
            } else if (objectToSave instanceof Node) {
                return ContentFactory.newContent(uri, (Node) objectToSave, ContentCreateOptions.newXmlInstance());
            } else if (objectToSave instanceof JsonNode) {
                return ContentFactory.newJsonContent(uri, (JsonNode) objectToSave, ContentCreateOptions.newJsonInstance());
            } else if (objectToSave instanceof XdmNode) {
                return ContentFactory.newContent(uri, (XdmNode) objectToSave, ContentCreateOptions.newXmlInstance());
            } else if (objectToSave instanceof File) {
                return ContentFactory.newContent(uri, (File) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof RandomAccessFile) {
                return ContentFactory.newContent(uri, (RandomAccessFile) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof URL) {
                return ContentFactory.newContent(uri, (URL) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof URI) {
                return ContentFactory.newContent(uri, (URI) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof String) {
                return ContentFactory.newContent(uri, (String) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof byte[]) {
                return ContentFactory.newContent(uri, (byte[]) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            } else if (objectToSave instanceof InputStream) {
                return ContentFactory.newContent(uri, (InputStream) objectToSave, new ContentCreateOptions()); // How to determine Content Type ?
            }
        } catch(IOException ioe) {
            throw new MappingException("Unable to access resource!", ioe);
        }

        throw new MappingException("Unexpected content type " + objectToSave.getClass());
    }

    private String determineCollectionName(Class<?> entityClass) {
        if (entityClass == null) {
            throw new InvalidDataAccessApiUsageException("No class parameter provided, entity can't be determined!");
        }

        MarklogicPersistentEntity<?> entity = mappingContext.getPersistentEntity(entityClass);
        if (entity == null) {
            throw new InvalidDataAccessApiUsageException("No Persistent Entity information found for the class " + entityClass.getName());
        }

        return entity.getDefaultCollection();
    }

    private MarklogicPersistentProperty getIdPropertyFor(Class<?> type) {
        MarklogicPersistentEntity<?> persistentEntity = mappingContext.getPersistentEntity(type);
        return persistentEntity == null ? null : persistentEntity.getIdProperty();
    }

    private static MarklogicConverter getDefaultMarklogicConverter() {
        return new MappingMarklogicConverter(new MarklogicMappingContext());
    }

    /**
     * Returns a SpEL {@link Expression} for the uri pattern expressed if present or {@literal null} otherwise.
     * Will also return {@literal null} if the uri pattern {@link String} evaluates
     * to a {@link LiteralExpression} (indicating that no subsequent evaluation is necessary).
     *
     * @param urlPattern can be {@literal null}
     * @return
     */
    private static Expression detectExpression(String urlPattern) {
        if (!StringUtils.hasText(urlPattern)) {
            return null;
        }

        Expression expression = PARSER.parseExpression(urlPattern, ParserContext.TEMPLATE_EXPRESSION);

        return expression instanceof LiteralExpression ? null : expression;
    }

    private Request buildAdhocRequest(String query, MarklogicInvokeOperationOptions options, Session session) {
        Assert.notNull(query, "A query must be provided!");

        Request request = session.newAdhocQuery(query);
        if (options != null) {
            request.getOptions().setCacheResult(options.useCacheResult());
            addVariablesToRequest(options.params(), request);
        }
        return request;
    }

    private Request buildModuleRequest(String moduleName, MarklogicInvokeOperationOptions options, Session session) {
        Assert.notNull(moduleName, "A module name must be provided!");

        Request request = session.newModuleInvoke(moduleName);
        if (options != null) {
            addVariablesToRequest(options.params(), request);
        }
        return request;
    }

    private void addVariablesToRequest(Map<Object, Object> params, Request request) {
        if (params != null) {
            for (Map.Entry<Object, Object> entry : params.entrySet()) {
                request.setVariable(buildVariable(entry.getKey(), entry.getValue()));
            }
        }
    }

    private XdmVariable buildVariable(Object key, Object value) {
        XName xname = isFullQualifiedName(key) ? buildVariableName((QName) key) : new XName((String) key);
        return ValueFactory.newVariable(xname, buildVariableValue(value));
    }

    private XName buildVariableName(QName name) {
        return new XName(name.getNamespaceURI(), name.getLocalPart());
    }

    private boolean isFullQualifiedName(Object name) {
        return name instanceof QName;
    }

    private XdmValue buildVariableValue(Object value) {
        if (value == null) {
            return ValueFactory.newXSString("");
        }

        if (value instanceof String) {
            return ValueFactory.newXSString((String) value);
        }

        if (value instanceof Boolean) {
            return ValueFactory.newXSBoolean((Boolean) value);
        }

        if (value instanceof Integer) {
            return ValueFactory.newXSInteger((Integer) value);
        }

        if (value instanceof Long) {
            return ValueFactory.newXSInteger((Long) value);
        }

        ConversionService conversionService = this.marklogicConverter.getConversionService();

        if (conversionService.canConvert(value.getClass(), XdmValue.class)) {
            return conversionService.convert(value, XdmValue.class);
        } else {
            return ValueFactory.newXSString(value.toString());
        }
    }

    private <T> List<T> prepareResultList(ResultSequence resultSequence, Class<T> returnType) {
        List<T> resultList = null;
        if (resultSequence != null && !resultSequence.isEmpty() && returnType != null) {
            resultList = new ArrayList<>();
            while (resultSequence.hasNext()) {
                resultList.add(prepareResultItem(resultSequence.next(), returnType));
            }
        }
        return resultList;
    }

    @SuppressWarnings("unchecked")
    private <T> T prepareResultItem(ResultItem resultItem, Class<T> returnType) {

        if (returnType.equals(String.class)) {
            return (T)resultItem.asString();
        }

        ConversionService conversionService = this.marklogicConverter.getConversionService();

        if (conversionService.canConvert(resultItem.getClass(), returnType)) {
            return conversionService.convert(resultItem, returnType);
        } else {
            throw new ConverterNotFoundException(TypeDescriptor.forObject(resultItem), TypeDescriptor.valueOf(returnType));
        }
    }

    private String retrieveTargetCollection(String defaultCollection) {
        if (defaultCollection == null) {
            return "fn:collection()";
        }

        return "fn:collection('" + defaultCollection + "')";
    }

    private String retrieveConstraintCollection(String defaultCollection) {
        if (defaultCollection == null) {
            return null;
        }

        return "cts:collection-query('" + defaultCollection + "')";
    }

    private <T> MarklogicIdentifier resolveMarklogicIdentifier(Object object) {
        return resolveMarklogicIdentifier(retrieveIdentifier(object), getIdPropertyFor(object.getClass()));
    }

    private Object retrieveIdentifier(Object object) {
        MarklogicPersistentProperty idProperty = getIdPropertyFor(object.getClass());

        if (idProperty == null)
            throw new  InvalidDataAccessApiUsageException("Unable to retrieve expected identifier property !");

        MarklogicPersistentEntity<?> entity = mappingContext.getPersistentEntity(object.getClass());
        return entity.getPropertyAccessor(object).getProperty(idProperty);
    }

    private <T> MarklogicIdentifier resolveMarklogicIdentifier(Object id, Class<T> entityClass) {
        MarklogicPersistentProperty idProperty = getIdPropertyFor(entityClass);

        if (idProperty == null)
            throw new  InvalidDataAccessApiUsageException("Unable to retrieve expected identifier property !");

        return resolveMarklogicIdentifier(id, idProperty);
    }

    private MarklogicIdentifier resolveMarklogicIdentifier(Object id, MarklogicPersistentProperty idProperty) {
        if (idProperty.getType().isPrimitive() || idProperty.getType().equals(String.class)) {
            return new MarklogicIdentifier() {
                @Override
                public QName qname() {
                    return idProperty.getQName();
                }

                @Override
                public String value() {
                    return id.toString();
                }
            };
        }

        ConversionService conversionService = marklogicConverter.getConversionService();
        if (conversionService.canConvert(idProperty.getType(), MarklogicIdentifier.class)) {
            return conversionService.convert(id, MarklogicIdentifier.class);
        }

        throw new MappingException("Unexpected identifier type " + idProperty.getClass());
    }

    interface DocumentExpressionContext {
        Object getEntity();

        Object getId();
    }

}