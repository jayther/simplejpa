package com.spaceprogram.simplejpa.query;

/**
 * User: treeder
 * Date: Feb 8, 2008
 * Time: 7:26:42 PM
 */
public class AbstractJPQLQuery implements Cloneable {
    public static final String[] SINGLE_STRING_KEYWORDS = {
            "SELECT", "UPDATE", "DELETE", "UNIQUE", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "OFFSET"

    };
    private String result;
    private String from;
    private String filter;
    private String ordering;
    private int offset = -1;

    public void setGrouping(String groupingClause) {

    }

    public void setResult(String result) {
        this.result = result;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public void setOrdering(String ordering) {
        this.ordering = ordering;
    }
    
    public void setOffset(int offset) {
    	this.offset = offset;
    }

    public String getFilter() {
        return filter;
    }

    public String getFrom() {
        return from;
    }

    public String getOrdering() {
        return ordering;
    }

    public String getResult() {
        return result;
    }
    
    public int getOffset() {
    	return offset;
    }
    public boolean hasOffset() {
    	return offset != -1;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    @Override
    public String toString() {
        return "SELECT " + getResult() + " FROM " + getFrom() + " WHERE " + getFilter() + " ORDER BY " + getOrdering();
    }
    public String createOffsetString() {
    	if (hasOffset()) {
    		return "SELECT count(*) FROM " + getFrom() + " WHERE " + getFilter() + " ORDER BY " + getOrdering() + " LIMIT " + getOffset();
    	}
    	return null;
    }
}
