# Rebuild the entrypoint helper with a patched Go standard library.
FROM golang:1.27.3-alpine AS gosu-build
ADD https://github.com/tianon/gosu.git#1.19 /src
WORKDIR /src
RUN CGO_ENABLED=0 go build -trimpath -o /gosu .

FROM postgres:17-alpine AS vector-build
ADD https://github.com/pgvector/pgvector.git#v0.8.7 /src
# Keep compilers out of the runtime. Portable code avoids host-specific CPU flags.
RUN apk add --no-cache build-base \
    && cd /src \
    && make OPTFLAGS="" with_llvm=no \
    && make install with_llvm=no DESTDIR=/out

FROM postgres:17-alpine
RUN apk upgrade --no-cache
COPY --from=vector-build /out/ /
COPY --from=vector-build /src/LICENSE /usr/share/doc/pgvector/LICENSE
COPY --from=gosu-build /gosu /usr/local/bin/gosu
RUN gosu nobody id
