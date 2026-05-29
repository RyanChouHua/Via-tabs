import { exec } from 'kernelsu';

const output = document.getElementById('output');

function setOutput(text) {
  output.textContent = text || '';
}

async function run(command) {
  try {
    const result = await exec(command);
    const text = [
      '$ ' + command,
      'errno=' + result.errno,
      result.stdout || '',
      result.stderr || ''
    ].filter(Boolean).join('\n');
    setOutput(text);
  } catch (error) {
    setOutput(String(error));
  }
}

document.getElementById('run').addEventListener('click', () => {
  run('sh /data/adb/modules/viatabs_root_helper/common/export-via-db.sh once');
});

document.getElementById('status').addEventListener('click', () => {
  run('sh /data/adb/modules/viatabs_root_helper/common/export-via-db.sh status');
});

document.getElementById('log').addEventListener('click', () => {
  run('sh /data/adb/modules/viatabs_root_helper/common/export-via-db.sh log');
});

document.getElementById('clear').addEventListener('click', () => {
  run('sh /data/adb/modules/viatabs_root_helper/common/export-via-db.sh clear-log');
});
