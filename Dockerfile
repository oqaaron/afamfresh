FROM php:8.2-apache

# ca-certificates is listed explicitly rather than left to arrive as a
# transitive dependency of curl: the outbound HTTPS calls in nominatim.php and
# auth.php verify peer certificates, and that verification needs a system trust
# store to resolve against. Dropping curl from this line should not silently
# disarm it.
RUN apt-get update && apt-get install -y \
    git curl ca-certificates libpng-dev libonig-dev libxml2-dev zip unzip \
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

# Drains notification_queue: the jobs NotificationManager enqueues for push and
# email. Nothing ran this before, so anything queued stayed queued forever.
#
# A shell loop rather than a PHP one, deliberately. If a bad job kills the PHP
# process, the next iteration starts a fresh one; a long-lived PHP daemon would
# die once and stop delivering silently. The script itself holds a lock file,
# so a slow run cannot overlap with the next tick.
#
# Output needs no redirect. This is started from start.sh, so it inherits the
# container's stdout, which is what the platform collects.
#
# It used to redirect to /proc/1/fd/1 to reach PID 1's stream. That file belongs
# to root, and once the worker correctly dropped to www-data the redirect was
# denied -- and a failed redirect means the shell never runs the command at all,
# so the drain silently never executed while the loop kept ticking. The only
# visible symptom was one "Permission denied" line a minute.
#
# Announces itself once at start-up rather than every tick: a per-minute
# heartbeat would bury the request log, but with no line at all a worker that
# is running and one that never started look identical.
RUN printf '#!/bin/sh\n\
echo "notification worker: started as $(id -un), draining every 60s"\n\
while true; do\n\
  php /var/www/html/cron/process_notifications.php 2>&1\n\
  sleep 60\n\
done\n' > /usr/local/bin/notify-worker.sh \
    && chmod +x /usr/local/bin/notify-worker.sh

# Cloud Run and Render both hand the port to the container in $PORT and health
# check that exact port; a hardcoded 80 fails on both. Rewritten at start-up
# rather than build time because the value is not known until the platform
# starts the container. Defaults to 80 so `docker run` locally still works.
#
# The worker is backgrounded and apache2-foreground stays PID 1: the platform
# watches PID 1 to decide whether the container is healthy, so the web server
# must remain the process that defines the container's life, not the worker.
#
# It runs as www-data, not root. As root it wrote /tmp/cloudsql-server-ca.pem
# — the database's TLS trust anchor, mode 0600 — before Apache did, and
# www-data could then neither read nor replace it. Every API response came back
# with "Permission denied" warnings and no usable CA. A background job has no
# business holding privileges the web server does not.
#
# Migrations run here too, as www-data for the same reason, before anything
# else touches the database: after the port rewrite, before the notify-worker
# is backgrounded, before Apache starts serving traffic. `set -e` above means
# a failed migration aborts the container start instead of serving requests
# against a half-migrated schema. See scripts/run-migrations.php.
RUN printf '#!/bin/sh\n\
set -e\n\
PORT="${PORT:-80}"\n\
sed -i "s/^Listen .*/Listen ${PORT}/" /etc/apache2/ports.conf\n\
sed -i "s/<VirtualHost \\*:80>/<VirtualHost *:${PORT}>/" /etc/apache2/sites-available/000-default.conf\n\
su -s /bin/sh -c "php /var/www/html/scripts/run-migrations.php" www-data\n\
su -s /bin/sh -c /usr/local/bin/notify-worker.sh www-data &\n\
exec apache2-foreground\n' > /usr/local/bin/start.sh \
    && chmod +x /usr/local/bin/start.sh

EXPOSE 80
CMD ["/usr/local/bin/start.sh"]
