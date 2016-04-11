#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE USER docker;
    CREATE DATABASE hello_twitter;
    GRANT ALL PRIVILEGES ON DATABASE hello_twitter TO docker;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -f /docker-entrypoint-initdb.d/ddl hello_twitter

