FROM php:8.2-apache

RUN apt-get update && apt-get install -y \
    git curl libpng-dev libonig-dev libxml2-dev zip unzip \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN docker-php-ext-install pdo_mysql mbstring exif pcntl bcmath gd

RUN a2enmod rewrite

# .htaccess carries the rules that deny env.yaml, *.sql, source files and
# dotfiles. Without AllowOverride they are silently ignored and every one of
# those becomes downloadable — the same trap the file itself warns about.
RUN printf '<Directory /var/www/html>\n\
    Options -Indexes +FollowSymLinks\n\
    AllowOverride All\n\
    Require all granted\n\
</Directory>\n' > /etc/apache2/conf-available/afamfresh.conf \
    && a2enconf afamfresh

COPY api/ /var/www/html/

RUN chown -R www-data:www-data /var/www/html \
    && chmod -R 755 /var/www/html

# Cloud Run and Render both hand the port to the container in $PORT and health
# check that exact port; a hardcoded 80 fails on both. Rewritten at start-up
# rather than build time because the value is not known until the platform
# starts the container. Defaults to 80 so `docker run` locally still works.
RUN printf '#!/bin/sh\n\
set -e\n\
PORT="${PORT:-80}"\n\
sed -i "s/^Listen .*/Listen ${PORT}/" /etc/apache2/ports.conf\n\
sed -i "s/<VirtualHost \\*:80>/<VirtualHost *:${PORT}>/" /etc/apache2/sites-available/000-default.conf\n\
exec apache2-foreground\n' > /usr/local/bin/start.sh \
    && chmod +x /usr/local/bin/start.sh

EXPOSE 80
CMD ["/usr/local/bin/start.sh"]
