package org.springframework.data.marklogic.repository.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.marklogic.core.EntityInformationOperationOptions;
import org.springframework.data.marklogic.core.MarklogicOperations;
import org.springframework.data.marklogic.core.MarklogicTemplate;
import org.springframework.data.marklogic.repository.MarklogicRepository;
import org.springframework.data.marklogic.repository.query.MarklogicEntityInformation;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Repository base implementation for Marklogic.
 *
 * @author Stéphane Toussaint
 */
@Repository
@Transactional(readOnly = true)
public class SimpleMarklogicRepository<T, ID extends Serializable> implements MarklogicRepository<T, ID> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleMarklogicRepository.class);

    private final MarklogicOperations marklogicOperations;
    private final MarklogicEntityInformation<T, ID> entityInformation;

    /**
     * Creates a new {@link SimpleMarklogicRepository} for the given {@link MarklogicEntityInformation} and {@link MarklogicTemplate}.
     *
     * @param metadata must not be {@literal null}.
     * @param marklogicOperations must not be {@literal null}.
     */
    public SimpleMarklogicRepository(MarklogicEntityInformation<T, ID> metadata, MarklogicOperations marklogicOperations) {

        Assert.notNull(marklogicOperations);
        Assert.notNull(metadata);

        this.entityInformation = metadata;
        this.marklogicOperations = marklogicOperations;
    }

    @Override
    public List<T> findAll(Sort sort) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    @Transactional
    public <S extends T> S save(S entity) {
        Assert.notNull(entity, "entity must not be null");

        if (entityInformation.isNew(entity)) {
            marklogicOperations.insert(entity, new EntityInformationOperationOptions<>(entityInformation));
        } else {
            marklogicOperations.save(entity, new EntityInformationOperationOptions<>(entityInformation));
        }

        return entity;
    }

    @Override
    @Transactional
    public <S extends T> List<S> save(Iterable<S> entities) {
        Assert.notNull(entities, "entities must not be null");
        return StreamSupport.stream(entities.spliterator(), false).map(this::save).collect(Collectors.toList());
    }

    @Override
    public T findOne(ID id) {
        Assert.notNull(id, "The given id must not be null");
        return marklogicOperations.findById(id, entityInformation.getJavaType(), new EntityInformationOperationOptions<>(entityInformation));
    }

    @Override
    public boolean exists(ID id) {
        return findOne(id) != null;
    }

    @Override
    public List<T> findAll() {
        return marklogicOperations.find(new Object(), entityInformation.getJavaType(), new EntityInformationOperationOptions<>(entityInformation));
    }

    @Override
    public Iterable<T> findAll(Iterable<ID> ids) {
        return StreamSupport.stream(ids.spliterator(), false).map(this::findOne).collect(Collectors.toList());
    }

    @Override
    public long count() {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    @Transactional
    public void delete(ID id) {
        marklogicOperations.remove(id, entityInformation.getJavaType());
    }

    @Override
    @Transactional
    public void delete(T entity) {
        marklogicOperations.remove(entity);
    }

    @Override
    @Transactional
    public void delete(Iterable<? extends T> entities) {
        entities.forEach(this::delete);
    }

    @Override
    @Transactional
    public void deleteAll() {
        marklogicOperations.removeAll(entityInformation.getJavaType(), new EntityInformationOperationOptions<>(entityInformation));
    }

    @Override
    public <S extends T> S findOne(Example<S> example) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    public <S extends T> Iterable<S> findAll(Example<S> example) {
        Map<String, Object> constraints = new HashMap<>();
        S probe = example.getProbe();
        PropertyDescriptor[] propertyDescriptors = BeanUtils.getPropertyDescriptors(probe.getClass());
        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            if (!propertyDescriptor.getName().equals("class") && propertyDescriptor.getReadMethod() != null) {
                try {
                    Object constraint = propertyDescriptor.getReadMethod().invoke(probe);
                    if (constraint != null) {
                        constraints.put(propertyDescriptor.getName(), constraint);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    LOGGER.warn("Unable to read property " + propertyDescriptor);
                }
            }
        }

        List<?> result = marklogicOperations.find(constraints, probe.getClass());
        return (Iterable<S>) result;
    }

    @Override
    public <S extends T> Iterable<S> findAll(Example<S> example, Sort sort) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        throw new RuntimeException("Not implemented yet !");
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        throw new RuntimeException("Not implemented yet !");
    }

}