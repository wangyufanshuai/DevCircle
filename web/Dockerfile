FROM node:24-alpine AS build
WORKDIR /app
ARG VITE_NOTIFICATION_SOCKET_ENABLED=false
ENV VITE_NOTIFICATION_SOCKET_ENABLED=${VITE_NOTIFICATION_SOCKET_ENABLED}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
