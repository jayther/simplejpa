package com.spaceprogram.simplejpa;

import org.apache.commons.lang.StringUtils;

import javax.persistence.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * Kerry Wright
 */
public abstract class PersistentProperty {
    protected final List<OrderClause> orderBys;
    protected final Field field;

    protected PersistentProperty(Field field) {
        field.setAccessible(true);
        this.field = field;
        orderBys = parseOrderBy(field.getAnnotation(OrderBy.class));
    }

    public Object getProperty(Object target) {
        try {
        	return field.get(target);
            //return getGetter().invoke(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setProperty(Object target, Object value) {
        try {
        	field.set(target, value);
            //getSetter().invoke(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
    
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
    	return field.getAnnotation(annotationClass);
    }

    public Class<?> getPropertyClass() {
        Class clazz = field.getType();
        if (Collection.class.isAssignableFrom(clazz)) {
        	return (Class<?>)((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
            //return (Class<?>)((ParameterizedType)getGetter().getGenericReturnType()).getActualTypeArguments()[0];
        }
        return clazz;
    }

    public Class<?> getRawClass() {
    	return field.getType();
        //return getGetter().getReturnType();
    }

    public abstract String getFieldName();
    
    public String getName() {
    	return field.getName();
    }

    public boolean isLob() {
        return field.isAnnotationPresent(Lob.class);
    }

    public boolean isForeignKeyRelationship() {
        // TODO add support for non "mapped" OneToMany (ie: unidirectional one-to-many as multivalued attribute)
        return field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(ManyToMany.class);
    }

    public boolean isInverseRelationship() {
        return field.isAnnotationPresent(OneToMany.class);
    }

    public boolean isId() {
        return field.isAnnotationPresent(Id.class);
    }

    public boolean isVersioned() {
        return field.isAnnotationPresent(Version.class);
    }

    public EnumType getEnumType() {
        if (field.isAnnotationPresent(Enumerated.class)) {
            if (field.getAnnotation(Enumerated.class).value() == EnumType.STRING) return EnumType.STRING;
            else return EnumType.ORDINAL;
        }
        return null;
    }

    public String getMappedBy() {
        if (field.isAnnotationPresent(OneToMany.class)) {
            return field.getAnnotation(OneToMany.class).mappedBy();
        }
        else if (field.isAnnotationPresent(OneToOne.class)) {
            return field.getAnnotation(OneToMany.class).mappedBy();
        }
        else if (field.isAnnotationPresent(ManyToMany.class)) {
            return field.getAnnotation(ManyToMany.class).mappedBy();
        }
        return null;
    }

    public String getColumnName() {
        if (field.isAnnotationPresent(Column.class)) {
            Column column = field.getAnnotation(Column.class);
            if (column.name() != null && !column.name().trim().isEmpty()) {
                String columnName = column.name();
                return columnName;
            }
        }
        if (isForeignKeyRelationship()) {
            return NamingHelper.foreignKey(getFieldName());
        }
        if (isLob()) {
            return NamingHelper.lobKeyAttributeName(getFieldName());
        }
        if (isId()) {
            return NamingHelper.NAME_FIELD_REF;
        }
        return StringUtils.uncapitalize(getFieldName());
    }

    public List<OrderClause> getOrderClauses() {
        return orderBys;
    }

    List<OrderClause> parseOrderBy(OrderBy orderAnnotation) {
        if (orderAnnotation == null || orderAnnotation.value().trim().isEmpty()) return Collections.emptyList();

        List<OrderClause> clauses = new ArrayList<OrderClause>();
        for (String orderBy : orderAnnotation.value().split(",")) {
            orderBy = orderBy.trim();
            if(orderBy.isEmpty()) continue;

            String[] parts = orderBy.trim().split("\\s");
            if (parts.length == 1) {
                clauses.add(new OrderClause(parts[0], OrderClause.Order.ASC));
            }
            else if (parts.length == 2) {
                clauses.add(new OrderClause(parts[0], OrderClause.Order.valueOf(parts[1])));
            }
            else throw new IllegalArgumentException("Invalid order by clause: "+orderAnnotation.value());
        }
        return clauses;
    }

    @Override
    public String toString() {
        return getFieldName();
    }

    public static class OrderClause {
        public enum Order {
            ASC,
            DESC
        }
        public final String field;
        public final Order order;

        public OrderClause(String field, Order order) {
            this.field = field;
            this.order = order;
        }
    }
}
