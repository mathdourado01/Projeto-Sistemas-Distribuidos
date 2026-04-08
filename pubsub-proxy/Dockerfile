FROM python:3.9-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY pubsub_proxy.py .

CMD ["python", "pubsub_proxy.py"]