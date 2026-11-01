FROM python:3.12-slim
WORKDIR /src
COPY gateway/requirements.txt /src/gateway/requirements.txt
RUN pip install --no-cache-dir -r /src/gateway/requirements.txt
COPY gateway /src/gateway
EXPOSE 8000
CMD ["uvicorn", "gateway.server:application", "--host", "0.0.0.0", "--port", "8000"]
