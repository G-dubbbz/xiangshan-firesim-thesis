### Chipyard Docker setup for standard Verilator and FireSim metasim.

FROM ubuntu:22.04 AS base

ARG CHIPYARD_HASH=0acc1e1de2d3284bcd4d876956932a013ffe1949

LABEL maintainer="https://groups.google.com/forum/#!forum/chipyard"

SHELL ["/bin/bash", "-c"]

ENV CHIPYARD_DIST=/opt/chipyard-dist

# Dependencies needed before Chipyard's own Ubuntu setup script can run.
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bc \
        ca-certificates \
        console-setup \
        curl \
        git \
        keyboard-configuration \
        sudo \
        unzip \
        wget && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt

RUN git clone https://github.com/ucb-bar/chipyard.git "${CHIPYARD_DIST}" && \
    cd "${CHIPYARD_DIST}" && \
    git checkout "${CHIPYARD_HASH}"

# Chipyard 1.14.0 may regenerate lockfiles when the conda sysroot does not
# match the base OS glibc. Align it during the image build for reproducibility.
RUN cd "${CHIPYARD_DIST}" && \
    SYS_GLIBC=$(ldd --version | awk '/ldd/{print $NF}') && \
    sed -i "s/^\([[:space:]]*-[[:space:]]*sysroot_linux-64=\).*/\1${SYS_GLIBC}/" conda-reqs/chipyard-base.yaml

# Conda now uses `solver` instead of the older `experimental_solver` config key
# that Chipyard 1.14.0's installer still emits after updating base Conda.
RUN sed -i 's/experimental_solver/solver/g' "${CHIPYARD_DIST}/.github/scripts/install-conda.sh" && \
    "${CHIPYARD_DIST}/.github/scripts/install-conda.sh"

# Blobless submodule fetches keep Docker builds from indexing large optional
# histories. The conda-installed Git supports the filter flag used here.
RUN cd "${CHIPYARD_DIST}" && \
    git config --global http.version HTTP/1.1 && \
    git config --global submodule.fetchJobs 4 && \
    sed -i 's/git submodule update --init /git submodule update --init --filter=blob:none /g; s/--filter=blob:none --filter=blob:none/--filter=blob:none/g' \
        scripts/init-submodules-no-riscv-tools-nolog.sh && \
    sed -i 's/git submodule update --init $CYDIR\/tools\/install-circt/git submodule update --init --filter=blob:none $CYDIR\/tools\/install-circt/' \
        build-setup.sh

# BUILD IMAGE WITH STANDARD VERILATOR TOOLCHAINS

FROM base AS base-with-tools-build

SHELL ["/bin/bash", "-cl"]

# Create the container-owned conda env and initialize submodules.
RUN cd "${CHIPYARD_DIST}" && \
    export MAKEFLAGS="-j $(nproc)" && \
    conda activate base && \
    ./build-setup.sh --conda-env-name chipyard --skip-toolchain --skip-ctags --skip-precompile --skip-firesim --skip-marshal --skip-circt --skip-clean

# Build RISC-V toolchain collateral under /opt/conda/envs/chipyard.
RUN cd "${CHIPYARD_DIST}" && \
    export MAKEFLAGS="-j $(nproc)" && \
    source env.sh && \
    export PATH="/usr/bin:/bin:${PATH}" && \
    export RISCV="${CONDA_PREFIX}/riscv-tools" && \
    ./build-setup.sh --skip-conda --skip-submodules --skip-ctags --skip-precompile --skip-firesim --skip-marshal --skip-circt --skip-clean

# Finish the standard Verilator/CIRCT setup without FireSim or FireMarshal.
RUN cd "${CHIPYARD_DIST}" && \
    export MAKEFLAGS="-j $(nproc)" && \
    source env.sh && \
    export RISCV="${CONDA_PREFIX}/riscv-tools" && \
    ./build-setup.sh --skip-conda --skip-submodules --skip-toolchain --skip-firesim --skip-marshal

FROM base-with-tools-build AS base-with-tools

COPY chipyard-docker-entrypoint.sh /usr/local/bin/chipyard-docker-entrypoint.sh
RUN chmod +x /usr/local/bin/chipyard-docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/chipyard-docker-entrypoint.sh"]
CMD ["/bin/bash"]

# BUILD IMAGE WITH FIRESIM VERILATOR METASIM SUPPORT

FROM base-with-tools-build AS base-with-firesim-metasim-build

SHELL ["/bin/bash", "-cl"]

# Initialize FireSim as a Chipyard library and precompile its Scala sources.
# This does not build FPGA collateral or FireMarshal workload images.
RUN cd "${CHIPYARD_DIST}" && \
    export MAKEFLAGS="-j $(nproc)" && \
    source /opt/conda/etc/profile.d/conda.sh && \
    conda activate /opt/conda/envs/chipyard && \
    sed -i 's|conda activate chipyard|conda activate /opt/conda/envs/chipyard|' env.sh && \
    source env.sh && \
    export RISCV="${CONDA_PREFIX}/riscv-tools" && \
    ./build-setup.sh --skip-conda --skip-submodules --skip-toolchain --skip-ctags --skip-marshal --skip-circt --skip-clean

FROM base-with-firesim-metasim-build AS base-with-firesim-metasim

COPY chipyard-docker-entrypoint.sh /usr/local/bin/chipyard-docker-entrypoint.sh
RUN chmod +x /usr/local/bin/chipyard-docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/chipyard-docker-entrypoint.sh"]
CMD ["/bin/bash"]

# BUILD OPTIONAL IMAGE WITH FIREMARSHAL INITIALIZED

FROM base-with-firesim-metasim-build AS base-with-firemarshal-build

SHELL ["/bin/bash", "-cl"]

# FireMarshal uses depmod while preparing kernel modules for base workloads.
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends kmod && \
    rm -rf /var/lib/apt/lists/*

SHELL ["/bin/bash", "-cl"]

# Initialize FireMarshal only. Workload image builds require loop mounts and
# should be run from a suitably privileged container at runtime.
RUN cd "${CHIPYARD_DIST}" && \
    source /opt/conda/etc/profile.d/conda.sh && \
    conda activate /opt/conda/envs/chipyard && \
    sed -i 's|conda activate chipyard|conda activate /opt/conda/envs/chipyard|' env.sh && \
    source env.sh && \
    cd software/firemarshal && \
    ./init-submodules.sh

FROM base-with-firemarshal-build AS base-with-firemarshal

COPY chipyard-docker-entrypoint.sh /usr/local/bin/chipyard-docker-entrypoint.sh
RUN chmod +x /usr/local/bin/chipyard-docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/chipyard-docker-entrypoint.sh"]
CMD ["/bin/bash"]
