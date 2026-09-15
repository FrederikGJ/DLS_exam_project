# Ollama with the concierge model baked in (dev plan DP-16). Built by docker-compose (`--profile ai`) as
# airport/ollama:local, so the same image can be loaded into kind:  kind load docker-image airport/ollama:local --name airport
#
# Two stages, no BuildKit features (plain `docker build` works):
#  1. The official image is the source of the ollama binary and its CPU inference backends. The image also ships
#     CUDA/MLX/Vulkan GPU backends (about 4.7 GB of a 9.2 GB image) that a laptop demo never uses; they are removed
#     in this stage so the COPY below only takes the CPU parts.
#  2. A plain Ubuntu (same base as the official image) gets the binary, the backends, the CA bundle (needed once,
#     to pull the model over HTTPS at build time) and the model. `ollama serve` runs in the background inside the
#     RUN, `ollama pull` downloads the model into OLLAMA_MODELS, and the server is stopped again before the layer
#     is committed. Result: ~1.2 GB instead of ~10 GB, and no download at start-up.
#
# Model: qwen2.5:1.5b (Q4_K_M, ~1 GB on disk, ~1.5 GB RAM when loaded). Switch model with
#     docker compose build --build-arg OLLAMA_MODEL=<name> ollama
# and set OLLAMA_MODEL=<name> for shop-service (compose environment / k8s ConfigMap).

FROM ollama/ollama:0.34.0 AS ollama
RUN rm -rf /usr/lib/ollama/cuda_v12 /usr/lib/ollama/cuda_v13 /usr/lib/ollama/mlx_cuda_v13 /usr/lib/ollama/vulkan

FROM ubuntu:24.04
ARG OLLAMA_MODEL=qwen2.5:1.5b
ENV OLLAMA_HOST=0.0.0.0:11434 \
    OLLAMA_MODELS=/root/.ollama/models
COPY --from=ollama /usr/bin/ollama /usr/bin/ollama
COPY --from=ollama /usr/lib/ollama /usr/lib/ollama
COPY --from=ollama /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN ollama serve > /tmp/ollama-build.log 2>&1 & \
    pid=$!; \
    for i in $(seq 1 60); do ollama list > /dev/null 2>&1 && break; sleep 1; done && \
    ollama pull "$OLLAMA_MODEL" && \
    ollama list && \
    kill "$pid" && (wait "$pid" || true) && \
    rm -f /tmp/ollama-build.log
EXPOSE 11434
ENTRYPOINT ["/usr/bin/ollama"]
CMD ["serve"]
