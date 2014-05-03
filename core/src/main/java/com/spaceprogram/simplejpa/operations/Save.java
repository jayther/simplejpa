package com.spaceprogram.simplejpa.operations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.CascadeType;
import javax.persistence.EnumType;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.PersistenceException;
import javax.persistence.PostPersist;
import javax.persistence.PostUpdate;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

import net.sf.cglib.proxy.Factory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.simpledb.model.Attribute;
import com.amazonaws.services.simpledb.model.DeleteAttributesRequest;
import com.amazonaws.services.simpledb.model.PutAttributesRequest;
import com.amazonaws.services.simpledb.model.ReplaceableAttribute;
import com.amazonaws.services.simpledb.model.UpdateCondition;
import com.spaceprogram.simplejpa.AnnotationInfo;
import com.spaceprogram.simplejpa.EntityManagerFactoryImpl;
import com.spaceprogram.simplejpa.EntityManagerSimpleJPA;
import com.spaceprogram.simplejpa.LazyInterceptor;
import com.spaceprogram.simplejpa.PersistentProperty;

/**
 * User: treeder Date: Apr 1, 2008 Time: 11:51:16 AM
 */
public class Save implements Callable {
    private static Logger logger = Logger.getLogger(Save.class.getName());

    private EntityManagerSimpleJPA em;
    private Object o;
    private String id;
    private boolean newObject;

    public Save(EntityManagerSimpleJPA entityManager, Object o) {
        this.em = entityManager;
        this.o = o;
        long start = System.currentTimeMillis();
        id = prePersist(o);
        if (logger.isLoggable(Level.FINE))
            logger.fine("prePersist time=" + (System.currentTimeMillis() - start));

    }

    /**
     * Checks that object is an entity and assigns an ID.
     * 
     * @param o
     * @return
     */
    private String prePersist(Object o) {
        em.checkEntity(o);
        // create id if required
        String id = em.getId(o);
        if (id == null || id.isEmpty()) {
            newObject = true;
            id = UUID.randomUUID().toString();
            // System.out.println("new object, setting id");
            AnnotationInfo ai = em.getFactory().getAnnotationManager().getAnnotationInfo(o);
            em.setFieldValue(o.getClass(), o, ai.getIdProperty(), Collections.singleton(id));
        }
        em.cachePut(id, o);
        return id;
    }

    public Object call() throws Exception {
        try {
            persistOnly(o, id);
        } catch (Exception e) {
            e.printStackTrace();
//            System.out.println("CAUGHT AND RETHROWING");
            throw e;
        }
        return o;
    }

    protected void persistOnly(Object o, String id) throws AmazonClientException, IllegalAccessException,
            InvocationTargetException, IOException {
        long start = System.currentTimeMillis();
        //System.out.println("persistOnly: called " + o.getClass().getAnnotations().toString());
        em.invokeEntityListener(o, newObject ? PrePersist.class : PreUpdate.class);
        AnnotationInfo ai = em.getFactory().getAnnotationManager().getAnnotationInfo(o);

        UpdateCondition expected = null;
        PersistentProperty versionField = null;
        Long nextVersion = -1L;

        String domainName;
        if (ai.getRootClass() != null) {
            domainName = em.getOrCreateDomain(ai.getRootClass());
        } else {
            domainName = em.getOrCreateDomain(o.getClass());
        }
        // Item item = DomainHelper.findItemById(this.em.getSimpleDb(),
        // domainName, id);
        // now set attributes
        List<ReplaceableAttribute> attsToPut = new ArrayList<ReplaceableAttribute>();
        List<Attribute> attsToDelete = new ArrayList<Attribute>();
        if (ai.getDiscriminatorValue() != null) {
            attsToPut.add(new ReplaceableAttribute(EntityManagerFactoryImpl.DTYPE, ai.getDiscriminatorValue(), true));
        }

        LazyInterceptor interceptor = null;
        if (o instanceof Factory) {
            Factory factory = (Factory) o;
            /*
             * for (Callback callback2 : factory.getCallbacks()) {
             * if(logger.isLoggable(Level.FINER)) logger.finer("callback=" +
             * callback2); if (callback2 instanceof LazyInterceptor) {
             * interceptor = (LazyInterceptor) callback2; } }
             */
            interceptor = (LazyInterceptor) factory.getCallback(0);
        }

        for (PersistentProperty field : ai.getPersistentProperties()) {
        	//System.out.println("Rawr: " + field.getFieldName());
            Object ob = field.getProperty(o);

            String columnName = field.getColumnName();
            if (ob == null) {
                attsToDelete.add(new Attribute(columnName, null));
                continue;
            }
            if (field.isForeignKeyRelationship()) {
                // store the id of this object
                if (Collection.class.isAssignableFrom(field.getRawClass())) {
                    for (Object each : (Collection) ob) {
                        String id2 = em.getId(each);
                        attsToPut.add(new ReplaceableAttribute(columnName, id2, true));
                    }
                } else {
                    String id2 = em.getId(ob);
                    attsToPut.add(new ReplaceableAttribute(columnName, id2, true));

                    /* check if we should persist this */
                    boolean persistRelationship = false;
                    ManyToOne a = field.getAnnotation(ManyToOne.class);
                    if (a != null && null != a.cascade()) {
                        CascadeType[] cascadeType = a.cascade();
                        for (CascadeType type : cascadeType) {
                            if (CascadeType.ALL == type || CascadeType.PERSIST == type) {
                                persistRelationship = true;
                            }
                        }
                    }
                    if (persistRelationship) {
                        em.persist(ob);
                    }
                }
            } else if (field.isVersioned()) {
                Long curVersion = Long.parseLong("" + ob);
                nextVersion = (1 + curVersion);

                attsToPut.add(new ReplaceableAttribute(columnName, em.padOrConvertIfRequired(nextVersion), true));

                if (curVersion > 0) {
                    expected = new UpdateCondition(columnName, em.padOrConvertIfRequired(curVersion), true);
                } else {
                    expected = new UpdateCondition().withName(columnName).withExists(false);
                }

                versionField = field;
            } else if (field.isInverseRelationship()) {
                // FORCING BI-DIRECTIONAL RIGHT NOW SO JUST IGNORE
                // ... except for cascading persistence down to all items in the
                // OneToMany collection
                /* check if we should persist this */
                boolean persistRelationship = false;
                OneToMany a = field.getAnnotation(OneToMany.class);
                CascadeType[] cascadeType = a.cascade();
                for (CascadeType type : cascadeType) {
                    if (CascadeType.ALL == type || CascadeType.PERSIST == type) {
                        persistRelationship = true;
                    }
                }
                if (persistRelationship) {
                    if (ob instanceof Collection) {
                        // it's OneToMany, so this should always be the case,
                        // shouldn't it?
                        for (Object _item : (Collection) ob) {
                            // persist each item in the collection
                            em.persist(_item);
                        }
                    }
                }

            } else if (field.isLob()) {
                // store in s3
                AmazonS3 s3 = null;
                // todo: need to make sure we only store to S3 if it's changed,
                // too slow.
                logger.fine("putting lob to s3");
                long start3 = System.currentTimeMillis();
                s3 = em.getS3Service();
                String bucketName = em.getS3BucketName();
                String s3ObjectId = id + "-" + field.getFieldName();

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(bos);
                out.writeObject(ob);
                byte[] contentBytes = bos.toByteArray();
                out.close();
                InputStream input = new ByteArrayInputStream(contentBytes);

                s3.putObject(bucketName, s3ObjectId, input, null);

                em.statsS3Put(System.currentTimeMillis() - start3);
                logger.finer("setting lobkeyattribute=" + columnName + " - " + s3ObjectId);
                attsToPut.add(new ReplaceableAttribute(columnName, s3ObjectId, true));
            } else if (field.getEnumType() != null) {
                String toSet = getEnumValue(field, o);
                attsToPut.add(new ReplaceableAttribute(columnName, toSet, true));
            } else if (field.isId()) {
                continue;
            } else if (Collection.class.isInstance(ob)) {
                for (Object each : ((Collection) ob)) {
                    String toSet = each != null ? em.padOrConvertIfRequired(each) : "";
                    // todo: throw an exception if this is going to exceed
                    // maximum size, suggest using @Lob
                    attsToPut.add(new ReplaceableAttribute(columnName, toSet, true));
                }
            } else {
            	/*
                String toSet = ob != null ? em.padOrConvertIfRequired(ob) : "";
                // todo: throw an exception if this is going to exceed maximum
                // size, suggest using @Lob
                attsToPut.add(new ReplaceableAttribute(columnName, toSet, true));
                */

                String toSet = ob != null ? em.padOrConvertIfRequired(ob) : "";

                try {
                	// Check size of encoded value.
                	byte[] bytes = toSet.getBytes("UTF-8");
                	if (bytes.length > 1024) {
                        // Maximum size is exceeded; split value into multiple chunks.
                		int i = 0, pos = 0;
                		while (pos < bytes.length) {
                			int size = 1020;
                			// Beware: do not split encoded characters.
                			// (Additional bytes of an encoded character follow the pattern 10xxxxxx.)
                			while (pos + size < bytes.length && (bytes[pos + size] & 0xc0) == 0x80) {
                				size --;
                			}
                			String chunk = new String(Arrays.copyOfRange(bytes, pos, Math.min(pos + size, bytes.length)), "UTF-8");
                			// Add four digit counter.
                			String counter = Integer.toString(i / 1000 % 10) + Integer.toString(i / 100 % 10) + Integer.toString(i / 10 % 10) + Integer.toString(i % 10);
                            attsToPut.add(new ReplaceableAttribute(columnName, chunk + counter, i == 0));
                            i ++;
                            pos += size;
                		}
                	} else {
                		// Simply store string as single value.
                        attsToPut.add(new ReplaceableAttribute(columnName, toSet, true));
                	}
                } catch (UnsupportedEncodingException x) {
                    // should never happen
                    throw new PersistenceException("Encoding 'UTF-8' is not supported!", x);
                }
            }
        }

        // Now finally send it for storage (If have attributes to add)
        long start2 = System.currentTimeMillis();
        long duration2;
        if (!attsToPut.isEmpty()) {
            this.em.getSimpleDb().putAttributes(
                    new PutAttributesRequest().withDomainName(domainName).withItemName(id).withAttributes(attsToPut)
                            .withExpected(expected));
            duration2 = System.currentTimeMillis() - start2;
            if (logger.isLoggable(Level.FINE))
                logger.fine("putAttributes time=" + (duration2));
            em.statsAttsPut(attsToPut.size(), duration2);

            if (null != versionField)
                versionField.setProperty(o, nextVersion);
        }

        /*
         * Check for nulled attributes so we can send a delete call. Don't
         * delete attributes if this is a new object AND don't delete atts if
         * it's not dirty AND don't delete if no nulls were set (nulledField on
         * LazyInterceptor)
         */
        if (interceptor != null) {
            if (interceptor.getNulledFields() != null && interceptor.getNulledFields().size() > 0) {
                List<Attribute> attsToDelete2 = new ArrayList<Attribute>();
                for (String s : interceptor.getNulledFields().keySet()) {
                    String columnName = ai.getPersistentProperty(s).getColumnName();
                    attsToDelete2.add(new Attribute(columnName, null));
                }
                start2 = System.currentTimeMillis();
                this.em.getSimpleDb().deleteAttributes(
                        new DeleteAttributesRequest().withDomainName(domainName).withItemName(id)
                                .withAttributes(attsToDelete2));

                // todo: what about lobs? need to delete from s3
                duration2 = System.currentTimeMillis() - start2;
                logger.fine("deleteAttributes time=" + (duration2));
                em.statsAttsDeleted(attsToDelete2.size(), duration2);
            } else {
                logger.fine("deleteAttributes time= no nulled fields, nothing to delete.");
            }
        } else {
            if (!newObject && attsToDelete.size() > 0) {
                // not enhanced, but still have to deal with deleted attributes
                start2 = System.currentTimeMillis();
                // for (ItemAttribute itemAttribute : attsToDelete) {
                // System.out.println("itemAttr=" + itemAttribute.getName() +
                // ": " + itemAttribute.getValue());
                // }
                this.em.getSimpleDb().deleteAttributes(
                        new DeleteAttributesRequest().withDomainName(domainName).withItemName(id)
                                .withAttributes(attsToDelete));
                // todo: what about lobs? need to delete from s3
                duration2 = System.currentTimeMillis() - start2;
                logger.fine("deleteAttributes time=" + (duration2));
                em.statsAttsDeleted(attsToDelete.size(), duration2);
            }
        }
        if (interceptor != null) {
            // reset the interceptor since we're all synced with the db now
            interceptor.reset();
        }
        em.invokeEntityListener(o, newObject ? PostPersist.class : PostUpdate.class);
        if (logger.isLoggable(Level.FINE))
            logger.fine("persistOnly time=" + (System.currentTimeMillis() - start));
    }

    static String getEnumValue(PersistentProperty field, Object ob) {
        EnumType enumType = field.getEnumType();
        Class retType = field.getPropertyClass();
        Object propertyValue = field.getProperty(ob);
        String toSet = null;
        if (enumType == EnumType.STRING) {
            toSet = propertyValue.toString();
        } else { // ordinal
            Object[] enumConstants = retType.getEnumConstants();
            for (int i = 0; i < enumConstants.length; i++) {
                Object enumConstant = enumConstants[i];
                if (enumConstant.equals(propertyValue)) {
                    toSet = Integer.toString(i);
                    break;
                }
            }
        }
        if (toSet == null) {
            // should never happen
            throw new PersistenceException("Enum value is null, couldn't find ordinal match: " + ob);
        }
        return toSet;
    }

}
