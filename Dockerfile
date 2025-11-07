# Build container for CMYK export server
FROM node:20-bullseye

# Set working directory for dependency installation
WORKDIR /app

# Copy package manifest first for better caching
COPY server/package*.json ./server/

# Install only production dependencies
WORKDIR /app/server
RUN npm install --production

# Copy application source
WORKDIR /app
COPY server ./server
COPY CoatedFOGRA39.icc ./

# Final runtime settings
WORKDIR /app/server
ENV PORT=3001
EXPOSE 3001

CMD ["node", "index.js"]
