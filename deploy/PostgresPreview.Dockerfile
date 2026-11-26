# Keep the official PostgreSQL 16 initialization behavior, but make the
# pinned init hook independent of Git's Windows checkout line-ending setting.
FROM postgres:16-bookworm
COPY init-preview-database.sh /docker-entrypoint-initdb.d/00-memo-preview.sh
RUN sed -i 's/\r$//' /docker-entrypoint-initdb.d/00-memo-preview.sh \
    && chmod 0755 /docker-entrypoint-initdb.d/00-memo-preview.sh
