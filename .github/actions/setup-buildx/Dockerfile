# Carrier file: NOTHING builds this image.
#
# It exists so that the BuildKit image the setup-buildx composite action boots (action.yml in this
# directory) is pinned by DIGEST and still kept current (audit item CI-SEC-13). The action reads
# the reference below at run time -- this line is the single place it is written -- and Dependabot's
# `docker` ecosystem, which .github/dependabot.yml points at this directory, proposes a new digest
# whenever `buildx-stable-1` moves. A bump PR therefore changes exactly what the release builds
# boot, and is reviewed like any other change to the release pipeline.
#
# Why a digest at all: the builder is the process that assembles every release image before it is
# signed. By tag alone it was whatever Docker Hub -- or, on the fallback path, mirror.gcr.io --
# served that minute. The mirror is verified to serve the byte-identical index for this digest.
#
# Keep the tag next to the digest: it says which channel the digest was taken from, and it is what
# Dependabot follows.
FROM moby/buildkit:buildx-stable-1@sha256:28a898719c18a33f4e8000685287fa36fd0dd9560c6440227d3a732d79bb41d8
