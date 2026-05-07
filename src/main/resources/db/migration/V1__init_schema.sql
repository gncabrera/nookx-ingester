create table scrape_page (
    id bigint not null auto_increment,
    source_code varchar(64) not null,
    url_hash varchar(64) not null,
    page_type varchar(64) not null,
    url varchar(2048) not null,
    natural_key varchar(255),
    storage_path varchar(1024),
    http_status integer,
    etag varchar(255),
    last_modified varchar(255),
    content_hash varchar(64),
    content_size_bytes bigint,
    fetch_status varchar(32) not null,
    fetch_retry_count integer not null default 0,
    fetch_last_error varchar(2000),
    parse_status varchar(32) not null,
    parse_retry_count integer not null default 0,
    parse_last_error varchar(2000),
    discovered_at datetime(6) not null,
    fetched_at datetime(6),
    parsed_at datetime(6),
    next_check_at datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint pk_scrape_page primary key (id),
    constraint ux_scrape_page_source_url unique (source_code, url_hash)
);

create index ix_scrape_page_fetch on scrape_page (source_code, fetch_status, next_check_at);
create index ix_scrape_page_parse on scrape_page (parse_status, fetched_at);

create table parsed_payload (
    id bigint not null auto_increment,
    source_code varchar(64) not null,
    ingest_target_code varchar(64) not null,
    payload_type varchar(255) not null,
    external_id varchar(255) not null,
    payload_json json not null,
    payload_hash varchar(64) not null,
    push_status varchar(32) not null,
    push_retry_count integer not null default 0,
    push_last_error varchar(2000),
    external_ref varchar(255),
    scrape_page_id bigint,
    first_seen_at datetime(6) not null,
    last_parsed_at datetime(6) not null,
    pushed_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint pk_parsed_payload primary key (id),
    constraint ux_parsed_payload_source_target_external unique (source_code, ingest_target_code, external_id),
    constraint fk_parsed_payload_scrape_page foreign key (scrape_page_id) references scrape_page(id)
);

create index ix_parsed_payload_target_status on parsed_payload (ingest_target_code, push_status, push_retry_count);
create index ix_parsed_payload_scrape_page on parsed_payload (scrape_page_id);

create table parsed_asset (
    id bigint not null auto_increment,
    parsed_payload_id bigint not null,
    kind varchar(32) not null,
    external_url_hash varchar(64) not null,
    external_url varchar(2048) not null,
    label varchar(255),
    sort_order integer,
    downloaded bit not null default b'0',
    download_retry_count integer not null default 0,
    download_last_error varchar(2000),
    local_path varchar(1024),
    content_hash varchar(64),
    content_type varchar(255),
    content_size_bytes bigint,
    push_status varchar(32) not null,
    push_retry_count integer not null default 0,
    push_last_error varchar(2000),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint pk_parsed_asset primary key (id),
    constraint ux_parsed_asset_payload_url unique (parsed_payload_id, external_url_hash),
    constraint fk_parsed_asset_payload foreign key (parsed_payload_id) references parsed_payload(id)
);

create index ix_parsed_asset_push on parsed_asset (push_status, push_retry_count);
create index ix_parsed_asset_download on parsed_asset (downloaded, download_retry_count);

create table job_run (
    id bigint not null auto_increment,
    stage varchar(32) not null,
    scope_type varchar(32) not null,
    scope_code varchar(128),
    trigger_type varchar(32) not null,
    triggered_by varchar(128),
    status varchar(32) not null,
    started_at datetime(6) not null,
    ended_at datetime(6),
    metrics_json json,
    error_message varchar(2000),
    constraint pk_job_run primary key (id)
);

create index ix_job_run_status on job_run (status, started_at);
create index ix_job_run_stage_scope on job_run (stage, scope_code, started_at);

create table job_log (
    id bigint not null auto_increment,
    job_run_id bigint not null,
    ts datetime(6) not null,
    level varchar(8) not null,
    logger_name varchar(255),
    message varchar(2000),
    context_json json,
    constraint pk_job_log primary key (id),
    constraint fk_job_log_run foreign key (job_run_id) references job_run(id)
);

create index ix_job_log_run on job_log (job_run_id, ts);
