FROM python:3.9-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY reference_service.py .
COPY chat_pb2.py .

CMD ["python", "reference_service.py"]