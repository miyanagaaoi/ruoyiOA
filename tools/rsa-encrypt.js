// 使用前端同一套 jsencrypt + 公钥，复现前端对 password 的 RSA 加密，用于接口级端到端验证
// jsencrypt 的 UMD 包会引用 window，Node 下需先补齐全局对象
global.window = global;
const mod = require('H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/node_modules/jsencrypt/bin/jsencrypt.min.js');
const JSEncrypt = mod.JSEncrypt || mod.default || global.JSEncrypt;

const publicKey = 'MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKoR8mX0rGKLqzcWmOzbfj64K8ZIgOdH\n' +
  'nzkXSOVOZbFu/TJhZ7rFAN+eaGkl3C4buccQd/EjEsj9ir7ijT7h96MCAwEAAQ==';

const encryptor = new JSEncrypt();
encryptor.setPublicKey(publicKey);
process.stdout.write(encryptor.encrypt(process.argv[2]) || '');
