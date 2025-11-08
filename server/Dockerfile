# Use an official Node.js runtime as the base image
FROM node:20-bullseye

# Create app directory
WORKDIR /app

# Install Ghostscript for PDF конвертации
RUN apt-get update \
  && apt-get install -y --no-install-recommends ghostscript \
  && rm -rf /var/lib/apt/lists/*

# Install dependencies first (better layer caching)
COPY package*.json ./
RUN npm install --production

# Copy application source
COPY . .

# The service listens on port 3001 by default
ENV PORT=3001
EXPOSE 3001

# Start the server
CMD ["node", "index.js"]
