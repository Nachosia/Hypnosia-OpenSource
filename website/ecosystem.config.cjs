module.exports = {
  apps: [{
    name: 'nachosia-site',
    script: './dist/boot.js',
    cwd: '/opt/nachosia/app',
    instances: 1,
    env: {
      NODE_ENV: 'production',
      PORT: '3001',
    },
    error_file: '/root/.pm2/logs/nachosia-site-error.log',
    out_file: '/root/.pm2/logs/nachosia-site-out.log',
    log_date_format: 'YYYY-MM-DD HH:mm:ss Z',
    merge_logs: true,
    autorestart: true,
    max_restarts: 10,
    min_uptime: '10s',
  }],
};
