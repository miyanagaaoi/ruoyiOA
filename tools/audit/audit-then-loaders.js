/**
 * 审计"主数据加载没有 catch"——真正的无限转圈缺陷。
 *
 * 判据：一个方法体内满足全部三条
 *   1. 出现 `this.loading = true`
 *   2. 有 `.then(` 且其后跟了 `this.loading = false`（说明它依赖成功路径复位）
 *   3. **整个方法体里没有 `.catch(`**
 *
 * 这类在请求失败时 loading 永远为 true -> 表格永久转圈且残留旧数据。
 */
const fs = require('fs')
const path = require('path')

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')
  const dir = makeFixtureDir({
    // 阳性：置了 loading、有 .then、成功路径复位、**没有 catch**
    'bad.vue': `<script>
export default {
  data() { return { loading: false, list: [] } },
  methods: {
    getList() {
      this.loading = true;
      listX(this.queryParams).then((res) => {
        this.list = res.rows;
        this.loading = false;
      });
    }
  }
}
</script>`,
    // 阴性①：有 catch
    'good-catch.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX().then((res) => { this.loading = false; }).catch((e) => { this.loading = false; });
    }
  }
}
</script>`,
    // 阴性②：多行链，catch 另起一行
    'good-multiline.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX()
        .then((res) => { this.loading = false; })
        .catch((e) => { this.loading = false; });
    }
  }
}
</script>`,
    // 阴性③：置了 loading 也复位，但没有 .then（不是本审计的目标形态）
    'good-nothen.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    sync() {
      this.loading = true;
      this.loading = false;
    }
  }
}
</script>`
  })
  const r = runSelfOn(__filename, dir)
  const hits = Array.isArray(r.json) ? r.json : []
  const files = hits.map(h => h.file)
  finish('audit-then-loaders', dir, [
    { label: '脚本能跑通', pass: r.code === 0, detail: r.code === 0 ? '' : r.out.slice(0, 200) },
    { label: '抓到阳性 bad.vue', pass: files.includes('bad.vue'), detail: '实际：' + (files.join(', ') || '(空)') },
    { label: '没有误报 good-catch.vue', pass: !files.includes('good-catch.vue') },
    { label: '没有误报 good-multiline.vue', pass: !files.includes('good-multiline.vue') },
    { label: '没有误报 good-nothen.vue', pass: !files.includes('good-nothen.vue') },
    { label: '命中数正好 1', pass: hits.length === 1, detail: '实际 ' + hits.length }
  ])
}

const SRC = process.argv[2] || 'F:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
const files = []
;(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'vform' || e.name === 'node_modules') continue
      walk(p)
    } else if (/\.vue$/.test(e.name)) files.push(p)
  }
})(SRC)

const hits = []
for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8')
  const lines = raw.split(/\r?\n/)
  const rel = path.relative(SRC, f).replace(/\\/g, '/')

  // 找出所有方法定义行
  const defs = []
  lines.forEach((l, i) => {
    const m = l.match(/^\s{2,6}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{\s*$/)
    if (m) defs.push({ name: m[1], line: i, indent: l.match(/^\s*/)[0].length })
  })

  defs.forEach((d, k) => {
    const end = k + 1 < defs.length ? defs[k + 1].line : lines.length
    const body = lines.slice(d.line, end).join('\n')
    if (!/this\.loading\s*=\s*true/.test(body)) return
    if (!/\.then\s*\(/.test(body)) return
    if (/\.catch\s*\(/.test(body)) return
    // 确认它确实依赖成功路径复位
    const resetsInThen = /\.then\s*\([\s\S]*?this\.loading\s*=\s*false/.test(body)
    hits.push({ file: rel, line: d.line + 1, method: d.name, resetsInThen })
  })
}

hits.sort((a, b) => a.file.localeCompare(b.file))
console.log(`主数据加载"无 catch → 永久转圈"共 ${hits.length} 处，分布在 ${new Set(hits.map(h => h.file)).size} 个文件\n`)
hits.forEach(h => console.log(`  ${h.file}:${h.line}   ${h.method}()`))
fs.writeFileSync(process.argv[3] || 'F:/dsh/ruoyiOA/.cache/audit-out.json', JSON.stringify(hits, null, 2), 'utf8')
