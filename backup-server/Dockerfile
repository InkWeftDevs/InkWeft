FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && mkdir /state && chown 10001:10001 /state
COPY server.py client.py shadow.py .
USER 10001:10001
EXPOSE 18751
CMD ["python", "server.py", "--db", "/state/backup.db", "--host", "0.0.0.0", "--port", "18751"]
