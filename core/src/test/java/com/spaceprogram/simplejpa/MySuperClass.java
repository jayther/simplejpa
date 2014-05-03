package com.spaceprogram.simplejpa;

import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import java.io.Serializable;

/**
 * User: treeder
 * Date: Feb 16, 2008
 * Time: 11:29:51 AM
 */
@MappedSuperclass
public class MySuperClass implements Serializable {

    @Id
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
