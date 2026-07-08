FROM grafana/grafana-image-renderer:latest

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

COPY local.conf /etc/fonts/local.conf

RUN fc-cache -f

USER 65532:996
