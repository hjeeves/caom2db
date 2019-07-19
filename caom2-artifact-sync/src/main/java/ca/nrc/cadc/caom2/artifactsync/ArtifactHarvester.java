/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2019.                            (c) 2019.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 *  $Revision: 5 $
 *
 ************************************************************************
 */

package ca.nrc.cadc.caom2.artifactsync;

import ca.nrc.cadc.caom2.Artifact;
import ca.nrc.cadc.caom2.Observation;
import ca.nrc.cadc.caom2.ObservationState;
import ca.nrc.cadc.caom2.Plane;
import ca.nrc.cadc.caom2.access.AccessUtil;
import ca.nrc.cadc.caom2.artifact.ArtifactStore;
import ca.nrc.cadc.caom2.artifact.StoragePolicy;
import ca.nrc.cadc.caom2.harvester.HarvestResource;
import ca.nrc.cadc.caom2.harvester.state.HarvestSkipURI;
import ca.nrc.cadc.caom2.harvester.state.HarvestSkipURIDAO;
import ca.nrc.cadc.caom2.harvester.state.HarvestState;
import ca.nrc.cadc.caom2.harvester.state.HarvestStateDAO;
import ca.nrc.cadc.caom2.harvester.state.PostgresqlHarvestStateDAO;
import ca.nrc.cadc.caom2.persistence.ObservationDAO;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.net.HttpDownload;
import ca.nrc.cadc.net.TransientException;

import java.net.URI;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

import org.apache.log4j.Logger;

public class ArtifactHarvester implements PrivilegedExceptionAction<Integer>, ShutdownListener {

    public static final Integer DEFAULT_BATCH_SIZE = Integer.valueOf(1000);
    public static final String STATE_CLASS = Artifact.class.getSimpleName();
    public static final String PROPRIETARY = "proprietary";

    private static final Logger log = Logger.getLogger(ArtifactHarvester.class);

    private ObservationDAO observationDAO;
    private ArtifactStore artifactStore;
    private HarvestStateDAO harvestStateDAO;
    private HarvestSkipURIDAO harvestSkipURIDAO;
    private String collection; 
    private StoragePolicy storagePolicy;
    private int batchSize;
    private String source;
    private Date startDate;
    private DateFormat df;
    private String caomChecksum;
    private String storageChecksum;
    private String caomContentLength;
    private long storageContentLength;
	private String reason = "None";
	private String errorMessage;
    
    
    // reset each run
    long downloadCount = 0;
    int processedCount = 0;
    Date start = new Date();

    public ArtifactHarvester(ObservationDAO observationDAO, HarvestResource harvestResource,
                             ArtifactStore artifactStore, int batchSize) {

        this.observationDAO = observationDAO;
        this.artifactStore = artifactStore;
        this.batchSize = batchSize;
        this.source = harvestResource.getIdentifier();
        this.collection = harvestResource.getCollection();
        this.storagePolicy = artifactStore.getStoragePolicy(collection);
        String database = harvestResource.getDatabase();
        String schema = harvestResource.getSchema();
        this.harvestStateDAO = new PostgresqlHarvestStateDAO(observationDAO.getDataSource(), database, schema);
        this.harvestSkipURIDAO = new HarvestSkipURIDAO(observationDAO.getDataSource(), database, schema);

        this.startDate = null;
        
        df = DateUtil.getDateFormat(DateUtil.ISO_DATE_FORMAT, DateUtil.UTC);
    }

    @Override
    public Integer run() throws Exception {

        downloadCount = 0;
        processedCount = 0;
        start = new Date();
        
        int num = 0;

        try {
            // Determine the state of the last run
            HarvestState state = harvestStateDAO.get(source, STATE_CLASS);
            startDate = state.curLastModified;
            // harvest up to a little in the past because the head of
            // the sequence may be volatile
            long fiveMinAgo = System.currentTimeMillis() - 5 * 60000L;
            Date stopDate = new Date(fiveMinAgo);
            if (startDate == null) {
                log.info("harvest window: null " + df.format(stopDate) + " [" + batchSize + "]");
            } else {
                log.info("harvest window: " + df.format(startDate) + " " + df.format(stopDate) + " [" + batchSize + "]");
            }
            List<ObservationState> observationStates = observationDAO.getObservationList(collection, startDate,
                stopDate, batchSize + 1);
            
            // avoid re-processing the last successful one stored in
            // HarvestState (normal case because query: >= startDate)
            if (!observationStates.isEmpty()) {
                ListIterator<ObservationState> iter = observationStates.listIterator();
                ObservationState curBatchLeader = iter.next();
                if (curBatchLeader != null) {
                    if (state.curLastModified != null) {
                        log.debug("harvesState: " + format(state.curID) + ", " + df.format(state.curLastModified));
                    }
                    if (curBatchLeader.getMaxLastModified().equals(state.curLastModified)) {
                        Observation observation = observationDAO.get(curBatchLeader.getURI());
                        log.debug("current batch: " + format(observation.getID()) + ", " + df.format(curBatchLeader.getMaxLastModified()));
                        if (state.curID != null && state.curID.equals(observation.getID())) {
                            iter.remove();
                        }
                    }
                }
            }

            num = observationStates.size();
            log.info("Found: " + num);
            for (ObservationState observationState : observationStates) {

                try {
                    observationDAO.getTransactionManager().startTransaction();
                    Observation observation = observationDAO.get(observationState.getURI());
                    
                    if (observation == null) {
                        log.debug("Observation no longer exists: " + observationState.getURI());
                    } else {
                        // will make progress even on failures
                        state.curLastModified = observation.getMaxLastModified();
                        state.curID = observation.getID();
                        
                        for (Plane plane : observation.getPlanes()) {
                            for (Artifact artifact : plane.getArtifacts()) {
                                
                                Date releaseDate = AccessUtil.getReleaseDate(artifact, plane.metaRelease, plane.dataRelease);
                                if (releaseDate == null) {
                                    // null date means private
                                    log.debug("null release date, skipping");
                                } else {
                                    logStart(format(state.curID), artifact);
                                    boolean success = true;
                                    boolean addToSkip = false;
                                    boolean added = false;
                                    String message = null;
                                    errorMessage = null;
                                    processedCount++;
                                    
                                    if (releaseDate.after(start)) {
                                        // private and release date is not null, download in the future
                                        errorMessage = ArtifactHarvester.PROPRIETARY;
                                    }
                                    
                                    try {
                                    	// by default, do not add to the skip table
                                    	boolean correctCopy = true;
                                    	
                                    	// artifact is not in storage if storage policy is 'PUBLIC ONLY' and the artifact is proprietary
                                        if ((StoragePolicy.ALL == storagePolicy) || errorMessage == null) {
                                        	// correctCopy is false if: checksum mismatch, content length mismatch or not in storage
                                        	// "not in storage" includes failing to resolve the artifact URI
                                        	correctCopy = checkArtifactInStorage(artifact.getURI(), artifact.contentChecksum, artifact.contentLength);
                                            log.debug("Artifact " + artifact.getURI() + " with MD5 " + artifact
                                                .contentChecksum + " correct copy: " + correctCopy);
                                        }
                                        
                                        if ((StoragePolicy.PUBLIC_ONLY == storagePolicy && errorMessage == ArtifactHarvester.PROPRIETARY) || !correctCopy) {
                                            HarvestSkipURI skip = harvestSkipURIDAO.get(source, STATE_CLASS, artifact.getURI());
                                            if (skip == null) {
                                                // not in skip table, add it
                                                skip = new HarvestSkipURI(source, STATE_CLASS, artifact.getURI(), releaseDate, errorMessage);
                                                addToSkip = true;
                                            } else {
                                                message = "Artifact already exists in skip table.";
                                                if (errorMessage == ArtifactHarvester.PROPRIETARY) {
                                                    // artifact is private, update skip table
                                                    skip.setTryAfter(releaseDate);
                                                    skip.errorMessage = errorMessage;
                                                    addToSkip = true;
                                                }
                                            }
                                        	
                                            if (addToSkip) {
	                                            harvestSkipURIDAO.put(skip);
	                                            downloadCount++;
	                                            added = true;
                                            }
                                        }
                                    } catch (Exception ex) {
                                        success = false;
                                        message = "Failed to determine if artifact " + artifact.getURI() + " exists: " + ex.getMessage();
                                        log.error(message, ex);
                                    }
                                    
                                    logEnd(format(state.curID), artifact, success, added, message);
                                }
                            }
                        }
                    }

                    harvestStateDAO.put(state);
                    log.debug("Updated artifact harvest state.  Date: " + state.curLastModified);
                    log.debug("Updated artifact harvest state.  ID: " + format(state.curID));
                    
                    observationDAO.getTransactionManager().commitTransaction();
                    
                } catch (Throwable t) {
                    observationDAO.getTransactionManager().rollbackTransaction();
                    throw t;
                }
            }

            return num;
        } finally {
            logBatchEnd();
        }

    }
    
    private boolean checkContentLength(HttpDownload httpHead, Long contentLength) {
    	// no contentLength in a CAOM artifact is considered a match
    	if (contentLength == null) {
    		caomContentLength = "null";
    		return true;
    	} else if (contentLength == 0) {
    		caomContentLength = Long.toString(contentLength);
    		return true;
    	} else {
    		caomContentLength = Long.toString(contentLength);
    		storageContentLength = httpHead.getContentLength();
    		if (storageContentLength == contentLength) {
    			return true;
    		} else {
            	reason = "contentLengths are different";
            	errorMessage = reason;
            	return false;
    		}
    	}
    	

    }
    
    private boolean checkChecksum(HttpDownload httpHead, URI checksum) {       
        String expectedMD5 = artifactStore.getMD5Sum(checksum);
        log.debug("Expected MD5: " + expectedMD5);
        if (expectedMD5 == null) {
    		// no checksum in a CAOM artifact is considered a match
        	reason = "null checksum";
        	caomChecksum = "null";
        	return true;
        } else {
        	caomChecksum = expectedMD5;
        }
        
        String contentMD5 = httpHead.getContentMD5();
        log.debug("Found matching artifact with md5 " + contentMD5);
        storageChecksum = contentMD5;
        if (expectedMD5.equalsIgnoreCase(contentMD5)) {
        	return true;
        } else {
        	reason = "checksums are different";
        	errorMessage = reason;
        	return false;
        }
    }
    
    private boolean checkArtifactInStorage(URI artifactURI, URI checksum, Long contentLength) throws TransientException {
        URL url = artifactStore.resolveURI(artifactURI);
        if (url == null) {
        	reason = "could not resolve artifact URI";
        	errorMessage = reason;
            log.debug("Failed to resolve artifact URI: " + artifactURI);
            return false;
        }
        
        HttpDownload httpHead = artifactStore.downloadHeader(url);
        int respCode = httpHead.getResponseCode();
        log.debug("Response code: " + respCode);
        if (httpHead.getThrowable() == null && respCode == 200) {
            if (checkChecksum(httpHead, checksum)) {
            	return checkContentLength(httpHead, contentLength);
            } else {
            	return false;
            }
        }

        if (httpHead.getResponseCode() == 404) {
            log.debug("Artifact not found");
            reason = "artifact not in storage";
            errorMessage = reason;
            return false;
        }

        // redirects mean that a copy isn't local
        if (httpHead.getResponseCode() == 303 || httpHead.getResponseCode() == 302) {
            log.debug("Redirected to another source");
            reason = "artifact not in local storage";
            errorMessage = reason;
            return false;
        }

        if (httpHead.getThrowable() != null) {
            if (httpHead.getThrowable() instanceof TransientException) {
                log.debug("Transient Exception");
                reason = "transient exception: " + httpHead.getThrowable().getMessage();
                throw (TransientException) httpHead.getThrowable();
            }

            reason = "unexpected exception: " + httpHead.getThrowable().getMessage();
            throw new RuntimeException("Unexpected", httpHead.getThrowable());
        }

        reason = "unexpected response code: " + respCode;
        throw new RuntimeException("unexpected response code " + respCode);
    }
    
    private String format(UUID id) {
        if (id == null) {
            return "null";
        }
        return id.toString();
    }
    
    private void logStart(String observationID, Artifact artifact) {
        StringBuilder startMessage = new StringBuilder();
        startMessage.append("START: {");
        startMessage.append("\"observationID\":\"").append(observationID).append("\"");
        startMessage.append(",");
        startMessage.append("\"artifact\":\"").append(artifact.getURI()).append("\"");
        startMessage.append(",");
        startMessage.append("\"date\":\"").append(df.format(new Date())).append("\"");
        startMessage.append("}");
        log.info(startMessage.toString());
    }

    private void logEnd(String observationID, Artifact artifact, boolean success, boolean added, String message) {
        StringBuilder startMessage = new StringBuilder();
        startMessage.append("END: {");
        startMessage.append("\"observationID\":\"").append(observationID).append("\"");
        startMessage.append(",");
        startMessage.append("\"artifact\":\"").append(artifact.getURI()).append("\"");
        startMessage.append(",");
        startMessage.append("\"success\":\"").append(success).append("\"");
        startMessage.append(",");
        startMessage.append("\"added\":\"").append(added).append("\"");
        startMessage.append(",");
        startMessage.append("\"reason\":\"").append(reason).append("\"");
        startMessage.append(",");
        startMessage.append("\"caomChecksum\":\"").append(caomChecksum).append("\"");
        startMessage.append(",");
        startMessage.append("\"caomContentLength\":\"").append(caomContentLength).append("\"");
        startMessage.append(",");
        startMessage.append("\"storageChecksum\":\"").append(storageChecksum).append("\"");
        startMessage.append(",");
        startMessage.append("\"storageContentLength\":\"").append(storageContentLength).append("\"");
        startMessage.append(",");
        startMessage.append("\"collection\":\"").append(collection).append("\"");
        startMessage.append(",");
        if (message != null) {
            startMessage.append(",");
            startMessage.append("\"message\":\"").append(message).append("\"");
        }
        startMessage.append(",");
        startMessage.append("\"date\":\"").append(df.format(new Date())).append("\"");
        startMessage.append("}");
        log.info(startMessage.toString());
    }
    
    private void logBatchEnd() {
        StringBuilder batchMessage = new StringBuilder();
        batchMessage.append("ENDBATCH: {");
        batchMessage.append("\"total\":\"").append(processedCount).append("\"");
        batchMessage.append(",");
        batchMessage.append("\"added\":\"").append(downloadCount).append("\"");
        batchMessage.append(",");
        batchMessage.append("\"time\":\"").append(System.currentTimeMillis() - start.getTime()).append("\"");
        batchMessage.append(",");
        batchMessage.append("\"date\":\"").append(df.format(start)).append("\"");
        batchMessage.append("}");
        log.info(batchMessage.toString());
    }

    @Override
    public void shutdown() {
        logBatchEnd();
    }
    
}
