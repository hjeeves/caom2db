
create table caom2.Part
(
    name varchar(1024) not null,
    productType varchar(64),
    
-- optimisation
    metaReadAccessGroups tsvector default '',

-- internal
    metaRelease timestamp,
    obsID uuid not null,
    planeID uuid not null,
    artifactID uuid not null references caom2.Artifact (artifactID),
    partID uuid not null primary key,
    lastModified timestamp not null,
    maxLastModified timestamp not null,
    stateCode int not null,
    metaChecksum varchar(136) not null,
    accMetaChecksum varchar(136) not null
)
;

-- this is for Artifact join Part
create index i_artifactID on caom2.Part (artifactID)
;

-- tag the clustering index
cluster i_artifactID on caom2.Part
;

-- this is for asset updates
create index ip_planeID on caom2.Part (planeID)
;

