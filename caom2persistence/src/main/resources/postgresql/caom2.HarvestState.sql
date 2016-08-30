
create table caom2.HarvestState
(
    source          varchar(256) not null,
    cname           varchar(256)  not null,
    curLastModified timestamp,
    curID           uuid,

    lastModified    timestamp not null,
    stateID         uuid primary key using index tablespace caom_index -- change: UUID
)
tablespace caom_data
;

create unique index HarvestState_i1
    on caom2.HarvestState ( source,cname )
tablespace caom_index
;