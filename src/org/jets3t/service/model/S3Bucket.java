/*
 * jets3t : Java Extra-Tasty S3 Toolkit (for Amazon S3 online storage service)
 * This is a java.net project, see https://jets3t.dev.java.net/
 * 
 * Copyright 2006 James Murty
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.jets3t.service.model;

import java.util.Date;

import org.jets3t.service.acl.AccessControlList;

/**
 * Represents an S3 bucket.
 *  
 * @author James Murty
 */
public class S3Bucket extends BaseS3Object {
    private static final long serialVersionUID = -8646831898339939580L;
    
    public static final String METADATA_HEADER_CREATION_DATE = "Date";
	public static final String METADATA_HEADER_OWNER = "Owner";
    
    public static final String LOCATION_EUROPE = "EU";
    public static final String LOCATION_US = null;
	
	private String name = null;
	private AccessControlList acl = null;
    private String location = LOCATION_US;
    private boolean isLocationKnown = false;
    
    /**
     * Create a bucket without any name or location specified
     */
    public S3Bucket() {        
    }
    
    /**
     * Create a bucket with a name. All buckets in S3 share a single namespace, 
     * so choose a unique name for your bucket. 
     * @param name the name for the bucket
     */
    public S3Bucket(String name) {
        this.name = name;
    }
	
    /**
     * Create a bucket with a name and a location. All buckets in S3 share a single namespace, 
     * so choose a unique name for your bucket. 
     * @param name the name for the bucket
     * @param location A string representing the location. Legal values include
     * {@link #LOCATION_US} and null (which are equivalent), or 
     * {@link #LOCATION_EUROPE}.
     */
    public S3Bucket(String name, String location) {
        this.name = name;
        this.location = location;
        this.isLocationKnown = true;
    }

    public String toString() {
		return "S3Bucket [name=" + getName() +
            ",location=" + getLocation() +
            ",creationDate=" + getCreationDate() + ",owner=" + getOwner() 
            + "] Metadata=" + getMetadataMap();
	}
	
    /**
     * @return
     * the bucket's owner, or null if it is unknown. 
     */
	public S3Owner getOwner() {
		return (S3Owner) getMetadata(METADATA_HEADER_OWNER);
	}

	/**
	 * Sets the bucket's owner in S3 - this should only be used internally by JetS3t
	 * methods that retrieve information directly from S3.
	 *  
	 * @param owner
	 */
	public void setOwner(S3Owner owner) {
        addMetadata(METADATA_HEADER_OWNER, owner);
	}	

	/**
	 * @return
	 * the bucket's creation date, or null if it is unknown.
	 */
	public Date getCreationDate() {
		return (Date) getMetadata(METADATA_HEADER_CREATION_DATE);
	}
	
    /**
     * Sets the bucket's creation date in S3 - this should only be used internally by JetS3t
     * methods that retrieve information directly from S3.
     * 
     * @param creationDate
     */
	public void setCreationDate(Date creationDate) {
		addMetadata(METADATA_HEADER_CREATION_DATE, creationDate);
	}

	/**
	 * @return 
	 * the bucket's Access Control List, or null if it is unknown.
	 */
	public AccessControlList getAcl() {
		return acl;
	}

	/**
	 * Sets the bucket's Access Control List in S3 - this should only be used internally by J3tS3t
	 * methods that retrieve information directly from S3.
	 * 
	 * @param acl
	 */
	public void setAcl(AccessControlList acl) {
		this.acl = acl;
	}

	/**
	 * @return
	 * the name of the bucket.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Set the name of the bucket. All buckets in S3 share a single namespace, 
     * so choose a unique name for your bucket. 
	 * @param name the name for the bucket
	 */
	public void setName(String name) {
		this.name = name;
	}
    
    /**
     * Set's the bucket's location. This method should only be used internally by 
     * JetS3t methods that retrieve information directly from S3.
     * 
     * @param location
     * A string representing the location. Legal values include
     * {@link #LOCATION_US} and null (which are equivalent), or 
     * {@link #LOCATION_EUROPE}.
     */
    public void setLocation(String location) {
        this.location = location;
        this.isLocationKnown = true;
    }
    
    /**
     * @return
     * true if this object knows the bucket's location, false otherwise.
     */
    public boolean isLocationKnown() {
        return this.isLocationKnown;
    }
    
    /**
     * @return
     * the bucket's location represented as a string. "EU" 
     * denotes a bucket located in Europe, while null denotes a bucket located 
     * in the US. 
     */
    public String getLocation() {
        return location;
    }
	
}
