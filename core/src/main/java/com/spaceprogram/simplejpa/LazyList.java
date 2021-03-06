package com.spaceprogram.simplejpa;

import java.io.Serializable;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.PersistenceException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.simpledb.model.Attribute;
import com.amazonaws.services.simpledb.model.Item;
import com.amazonaws.services.simpledb.model.SelectResult;
import com.spaceprogram.simplejpa.query.JPAQuery;
import com.spaceprogram.simplejpa.query.QueryImpl;
import com.spaceprogram.simplejpa.query.SimpleDBQuery;
import com.spaceprogram.simplejpa.query.SimpleQuery;

import org.apache.commons.collections.list.GrowthList;

/**
 * Loads objects in the list on demand from SimpleDB. <p/> <p/> User: treeder Date: Feb 10, 2008 Time: 9:06:16 PM
 */
@SuppressWarnings("unchecked")
public class LazyList<E> extends AbstractList<E> implements Serializable {
    private static Logger logger = Logger.getLogger(LazyList.class.getName());

    private transient EntityManagerSimpleJPA em;
    private Class genericReturnType;
    private SimpleQuery origQuery;

    /**
     * Stores the actual objects for this list
     */
    private List<E> backingList;
    private String nextToken;
    private int count = -1;
    private String realQuery;
    private String domainName;
    private int maxResults = -1;
    private int maxResultsPerToken = SimpleQuery.MAX_RESULTS_PER_REQUEST;
    private boolean consistentRead = true;
    
    private boolean offsetExecuted = false;
    private String offsetQuery = null;
    
    private String offsetNextToken = null;
    
    private boolean loadAllOnSize = false;

    public LazyList(EntityManagerSimpleJPA em, Class tClass, SimpleQuery query) {
        this.em = em;
        this.genericReturnType = tClass;
        this.origQuery = query;
        this.maxResults = query.getMaxResults();
        this.consistentRead = query.isConsistentRead();
        if (query.hasOffset()) {
        	this.offsetQuery = SimpleDBQuery.convertToCountQuery(query.createAmazonQuery(false).getValue());
        }
        if (query.hasLimit()) {
        	setMaxResultsPerToken(query.getLimit());
        }
        AnnotationInfo ai = em.getAnnotationManager().getAnnotationInfo(genericReturnType);
        try {
            domainName = em.getDomainName(ai.getRootClass());
            if (domainName == null) {
                logger.warning("Domain does not exist for " + ai.getRootClass());
                backingList = new GrowthList(0);
            } else {
                // Do not include the limit in the query since will specify in loadAtLeastItems()
                realQuery = query.createAmazonQuery(false).getValue();
            }
        } catch (Exception e) {
            throw new PersistenceException(e);
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public int size() {
        if (count > -1) return count;

        if (loadAllOnSize) {
        	if (origQuery.hasLimit()) {
        		loadAtleastItems(origQuery.getLimit() - 1);
        	} else if (!noLimit()) {
        		loadAtleastItems(maxResults - 1);
        	} else {
        		loadAtleastItems(Integer.MAX_VALUE);
        	}
        } else {
        	calculateCountWithOffset();
        	return count;
        }
        if (backingList != null && nextToken == null) {
            count = backingList.size();
            return count;
        }
        return origQuery.getCount();
    }

    public int getFetchedSize() {
        return backingList == null ? 0 : backingList.size();
    }

    public void add(int index, E element) {
        backingList.add(index, element);
    }

    public E set(int index, E element) {
        return backingList.set(index, element);
    }

    public E remove(int index) {
        return backingList.remove(index);
    }

    public void setMaxResultsPerToken(int maxResultsPerToken) {
        // SimpleDB currently has a maximum limit of 2500
        this.maxResultsPerToken = Math.min(maxResultsPerToken, QueryImpl.MAX_RESULTS_PER_REQUEST);
    }

    public int getMaxResultsPerToken() {
        return maxResultsPerToken;
    }

    public E get(int i) {
        if (logger.isLoggable(Level.FINER))
            logger.finer("getting from lazy list at index=" + i);
        loadAtleastItems(i);
        return backingList.get(i);
    }

    private synchronized void loadAtleastItems(int index) {
        if ((backingList != null && nextToken == null) || (!noLimit() && index >= maxResults)) {
            return;
        }
        
        if (nextToken == null && offsetNextToken != null) {
        	nextToken = offsetNextToken;
        }

        if (backingList == null) {
            backingList = new GrowthList();
        }
        //run offset query if not yet executed
        if (offsetQuery != null && !offsetExecuted) {
        	offsetExecuted = true;
        	int expectedOffset = origQuery.getOffset();
        	int currentCount = 0;
        	while (currentCount < expectedOffset) {
        		SelectResult qr;
        		try {
        			if (logger.isLoggable(Level.FINER)) {
        				logger.finer("offset query for lazylist=" + offsetQuery);
        			}
        			int limit = expectedOffset - currentCount;
        			String limitQuery = offsetQuery + " limit " + limit;
                    if (em.getFactory().isPrintQueries())
                        System.out.println("offset query in lazylist=" + limitQuery);
                    qr = DomainHelper.selectItems(this.em.getSimpleDb(), limitQuery, nextToken, isConsistentRead());
                    for (Item item : qr.getItems()) {
                    	for (Attribute attribute : item.getAttributes()) {
                    		if (attribute.getName().equalsIgnoreCase("count")) {
                    			try {
                                    currentCount += Integer.parseInt(attribute.getValue());
                    			} catch (NumberFormatException e) {
                    				//do nothing
                    			}
                    		}
                    	}
                    }
                    
                    if (qr.getNextToken() == null) {
                    	nextToken = null;
                        break;
                    }
                    if (!noLimit() && currentCount == expectedOffset) {
                        break;
                    }

                    if (!noLimit() && currentCount > expectedOffset) {
                        throw new PersistenceException("Got more results than the offset.");
                    }

                    nextToken = qr.getNextToken();
        		} catch (AmazonClientException e) {
                    throw new PersistenceException("Offsest query failed: Domain=" + domainName + " -> " + origQuery + "; offset query: " + offsetQuery, e);
        		}
        	}
            
            if (nextToken == null) {
            	return;
            }
        }

        while (backingList.size() <= index) {
            SelectResult qr;
            try {
                if (logger.isLoggable(Level.FINER))
                    logger.finer("query for lazylist=" + origQuery);

                int limit = maxResults - backingList.size();
                String limitQuery = realQuery + " limit " + (noLimit() ? maxResultsPerToken : Math.min(maxResultsPerToken, limit));
                if (em.getFactory().isPrintQueries())
                    System.out.println("query in lazylist=" + limitQuery);
                qr = DomainHelper.selectItems(this.em.getSimpleDb(), limitQuery, nextToken, isConsistentRead());

                if (logger.isLoggable(Level.FINER))
                    logger.finer("got items for lazylist=" + qr.getItems().size());

                for (Item item : qr.getItems()) {
                    backingList.add((E) em.buildObject(genericReturnType, item.getName(), item.getAttributes()));
                }
                
                if (origQuery.hasLimit() && backingList.size() == origQuery.getLimit()) {
                	nextToken = null;
                	break;
                }

                if (qr.getNextToken() == null || (!noLimit() && qr.getItems().size() == limit)) {
                    nextToken = null;
                    break;
                }

                if (!noLimit() && qr.getItems().size() > limit) {
                    throw new PersistenceException("Got more results than the limit.");
                }

                nextToken = qr.getNextToken();
            } catch (AmazonClientException e) {
                throw new PersistenceException("Query failed: Domain=" + domainName + " -> " + origQuery, e);
            }
        }

    }
    
    private synchronized void calculateCountWithOffset() {
    	if (count > -1) {
    		return;
    	}

        if (offsetQuery != null && !offsetExecuted) {
        	offsetExecuted = true;
        	int expectedOffset = origQuery.getOffset();
        	int currentCount = 0;
        	while (currentCount < expectedOffset) {
        		SelectResult qr;
        		try {
        			if (logger.isLoggable(Level.FINER)) {
        				logger.finer("offset query for lazylist=" + offsetQuery);
        			}
        			int limit = expectedOffset - currentCount;
        			String limitQuery = offsetQuery + " limit " + limit;
                    if (em.getFactory().isPrintQueries())
                        System.out.println("offset query in lazylist=" + limitQuery);
                    qr = DomainHelper.selectItems(this.em.getSimpleDb(), limitQuery, offsetNextToken, isConsistentRead());
                    for (Item item : qr.getItems()) {
                    	for (Attribute attribute : item.getAttributes()) {
                    		if (attribute.getName().equalsIgnoreCase("count")) {
                    			try {
                                    currentCount += Integer.parseInt(attribute.getValue());
                    			} catch (NumberFormatException e) {
                    				//do nothing
                    			}
                    		}
                    	}
                    }
                    
                    if (qr.getNextToken() == null) {
                    	offsetNextToken = null;
                        break;
                    }
                    if (!noLimit() && currentCount == expectedOffset) {
                        break;
                    }

                    if (!noLimit() && currentCount > expectedOffset) {
                        throw new PersistenceException("Got more results than the offset.");
                    }

                    offsetNextToken = qr.getNextToken();
        		} catch (AmazonClientException e) {
                    throw new PersistenceException("Offsest query failed: Domain=" + domainName + " -> " + origQuery + "; offset query: " + offsetQuery, e);
        		}
        	}
            
            if (offsetNextToken == null) {
            	return;
            }
        }

        String nextToken = offsetNextToken;
        String countQuery = SimpleDBQuery.convertToCountQuery(realQuery);
        int currentCount = 0;
        do {
            SelectResult qr;
            try {
                if (logger.isLoggable(Level.FINER))
                    logger.finer("query for lazylist=" + origQuery);

                int limit = maxResults - currentCount;
                String limitQuery = countQuery + " limit " + (noLimit() ? maxResultsPerToken : Math.min(maxResultsPerToken, limit));
                if (em.getFactory().isPrintQueries())
                    System.out.println("query in lazylist=" + limitQuery);
                qr = DomainHelper.selectItems(this.em.getSimpleDb(), limitQuery, nextToken, isConsistentRead());
                
                int value = 0;
                for (Item item : qr.getItems()) {
                	for (Attribute attribute : item.getAttributes()) {
                		if (attribute.getName().equalsIgnoreCase("count")) {
                			try {
                                value = Integer.parseInt(attribute.getValue());
                			} catch (NumberFormatException e) {
                				//do nothing
                			}
                		}
                	}
                }
                currentCount += value;
                
                //if ((hasLimit() ))
                
                if (origQuery.hasLimit() && currentCount == origQuery.getLimit()) {
                	nextToken = null;
                	break;
                }
                
                if (!noLimit() && value == limit) {
                	nextToken = null;
                	break;
                }

                if (qr.getNextToken() == null) {
                    nextToken = null;
                    break;
                }

                if (!noLimit() && qr.getItems().size() > limit) {
                    throw new PersistenceException("Got more results than the limit.");
                }

                nextToken = qr.getNextToken();
            } catch (AmazonClientException e) {
                throw new PersistenceException("Query failed: Domain=" + domainName + " -> " + origQuery, e);
            }
        } while (nextToken != null);
        count = currentCount;
    }

    private boolean noLimit() {
        return maxResults < 0;
    }

    @Override
    public Iterator<E> iterator() {
        return new LazyListIterator();
    }

    public void setConsistentRead(boolean consistentRead) {
        this.consistentRead = consistentRead;
    }

    public boolean isConsistentRead() {
        return consistentRead;
    }

    public boolean isLoadAllOnSize() {
		return loadAllOnSize;
	}

	public void setLoadAllOnSize(boolean loadAllOnSize) {
		this.loadAllOnSize = loadAllOnSize;
	}

	private class LazyListIterator implements Iterator<E> {
        private int iNext = 0;

        public boolean hasNext() {
            loadAtleastItems(iNext);
            return backingList.size() > iNext;
        }

        public E next() {
            return get(iNext++);
        }

        public void remove() {
            LazyList.this.remove(iNext - 1);
        }
    }
}