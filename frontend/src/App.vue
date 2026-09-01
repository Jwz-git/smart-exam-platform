<template>
  <main class="shell">
    <section class="card">
      <p class="eyebrow">SMART EXAM PLATFORM</p>
      <h1>在线题库与组卷系统</h1>
      <p class="description">前端工程骨架已启动。下一阶段将实现登录、权限与题库管理。</p>
      <dl>
        <div><dt>前端</dt><dd>Vue 3 + TypeScript + Vite</dd></div>
        <div><dt>后端</dt><dd>Spring Boot REST API</dd></div>
        <div><dt>数据库</dt><dd>MySQL 8.4 + Flyway</dd></div>
      </dl>
    </section>
  </main>
</template>

<style>
:root { color: #17324d; background: #edf3f7; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; min-width: 320px; min-height: 100vh; }
.shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.card { width: min(680px, 100%); padding: 48px; border-radius: 24px; background: #fff; box-shadow: 0 18px 60px rgb(30 65 90 / 12%); }
.eyebrow { margin: 0 0 12px; color: #147d78; font-size: 13px; font-weight: 700; letter-spacing: .14em; }
h1 { margin: 0; font-size: clamp(32px, 7vw, 54px); line-height: 1.12; }
.description { margin: 20px 0 30px; color: #557086; font-size: 18px; line-height: 1.7; }
dl { display: grid; gap: 12px; margin: 0; }
dl div { display: grid; grid-template-columns: 88px 1fr; gap: 12px; padding-top: 12px; border-top: 1px solid #dce6ec; }
dt { color: #718798; }
dd { margin: 0; font-weight: 600; }
@media (max-width: 520px) { .card { padding: 32px 24px; } }
</style>
