const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || path.resolve(__dirname, '../../tools/node_modules/playwright'));
const browserOptions = process.env.BROWSER_EXECUTABLE_PATH
  ? { headless: true, executablePath: process.env.BROWSER_EXECUTABLE_PATH }
  : { headless: true, channel: 'msedge' };
module.exports = { chromium, browserOptions };
