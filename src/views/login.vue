<template>
  <!-- 登录页 · 集团 OA 设计语言（左品牌区 + 右表单区，窄屏隐藏品牌区） -->
  <div class="oa-login">
    <!-- 左：品牌区 -->
    <section class="brand">
      <img class="brand-logo" :src="logoUrl" alt="集团OA系统" />
      <h1 class="brand-title">集团OA系统</h1>
      <p class="brand-sub">
        集团—公司—部门—科室四级组织，事项 / 资金 / 合同 / 印鉴证照多类审批单，全流程留痕。
      </p>
      <ul class="brand-list">
        <li>审批人发起时快照，调岗离职不影响在途单据</li>
        <li>数据域由服务端强制过滤，越权不可见</li>
        <li>签名与轨迹只追加，审计可追溯</li>
      </ul>
      <p class="brand-foot">内部系统 · 请通过公司内网或 VPN 访问</p>
    </section>

    <!-- 右：表单区 -->
    <section class="panel">
      <div class="panel-inner">
        <header class="panel-head">
          <h2 class="panel-title">登录</h2>
          <p class="panel-sub">使用集团统一账号口令登录</p>
        </header>

        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form">
          <el-form-item prop="username">
            <el-input v-model="loginForm.username" type="text" auto-complete="off" placeholder="账号">
              <svg-icon slot="prefix" icon-class="user" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="loginForm.password" type="password" auto-complete="off" placeholder="密码" @keyup.enter.native="handleLogin" show-password>
              <svg-icon slot="prefix" icon-class="password" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item prop="code" v-if="captchaEnabled">
            <el-input v-model="loginForm.code" auto-complete="off" placeholder="验证码" style="width: 63%" @keyup.enter.native="handleLogin">
              <svg-icon slot="prefix" icon-class="validCode" class="el-input__icon input-icon" />
            </el-input>
            <div class="login-code">
              <img :src="codeUrl" @click="getCode" class="login-code-img" />
            </div>
          </el-form-item>
          <el-checkbox v-model="loginForm.rememberMe" class="remember">记住密码</el-checkbox>
          <el-form-item style="width:100%;">
            <el-button :loading="loading" size="medium" type="primary" style="width:100%;" @click.native.prevent="handleLogin">
              <span v-if="!loading">登 录</span>
              <span v-else>登 录 中...</span>
            </el-button>
            <div style="float: right;" v-if="register">
              <router-link class="link-type" :to="'/register'">立即注册</router-link>
            </div>
          </el-form-item>
        </el-form>

        <p class="panel-foot">{{ footerContent }}</p>
      </div>
    </section>
  </div>
</template>

<script>
import { getCodeImg } from "@/api/login";
import Cookies from "js-cookie";
import { encrypt, decrypt } from "@/utils/jsencrypt";
import { connectWs } from "@/utils/socket/handleMessage.js";
import defaultSettings from '@/settings'
import logoImg from '@/assets/logo/logo.png'

export default {
  name: "Login",
  data() {
    return {
      logoUrl: logoImg,
      footerContent: defaultSettings.footerContent,
      codeUrl: "",
      loginForm: {
        username: "",
        password: "",
        rememberMe: false,
        code: "",
        uuid: "",
      },
      loginRules: {
        username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
        password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
        code: [{ required: true, trigger: "change", message: "请输入验证码" }],
      },
      loading: false,
      // 验证码开关
      captchaEnabled: true,
      // 注册开关
      register: false,
      redirect: undefined,
    };
  },
  watch: {
    $route: {
      handler: function (route) {
        this.redirect = route.query && route.query.redirect;
      },
      immediate: true,
    },
  },
  created() {
    this.getCode();
    this.getCookie();
  },
  methods: {
    getCode() {
      getCodeImg().then((res) => {
        this.captchaEnabled = res.captchaEnabled === undefined ? true : res.captchaEnabled;
        if (this.captchaEnabled) {
          this.codeUrl = "data:image/gif;base64," + res.img;
          this.loginForm.uuid = res.uuid;
        }
      });
    },
    getCookie() {
      const username = Cookies.get("username");
      const password = Cookies.get("password");
      const rememberMe = Cookies.get("rememberMe");
      this.loginForm = {
        username: username === undefined ? this.loginForm.username : username,
        password: password === undefined ? this.loginForm.password : decrypt(password),
        rememberMe: rememberMe === undefined ? false : Boolean(rememberMe),
      };
    },
    handleLogin() {
      this.$refs.loginForm.validate((valid) => {
        if (valid) {
          this.loading = true;
          if (this.loginForm.rememberMe) {
            Cookies.set("username", this.loginForm.username, { expires: 30 });
            Cookies.set("password", encrypt(this.loginForm.password), { expires: 30 });
            Cookies.set("rememberMe", this.loginForm.rememberMe, { expires: 30 });
          } else {
            Cookies.remove("username");
            Cookies.remove("password");
            Cookies.remove("rememberMe");
          }
          this.$store
            .dispatch("Login", this.loginForm)
            .then(() => {
              connectWs(this.$wsApi, this.$store, this.$enums, this.$msgType, this.$eventBus, this.$alert, this.$message);
              this.$router.push({ path: this.redirect || "/" }).catch(() => {});
            })
            .catch(() => {
              this.loading = false;
              if (this.captchaEnabled) {
                this.getCode();
              }
            });
        }
      });
    },
  },
};
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
/* ============================================================
   登录页 · 集团 OA 设计语言（对齐 oa-web LoginView）
   左品牌区（surface-1 底 + 右侧 1px 细线）+ 右表单区（定宽 480px）
   令牌见 @/assets/styles/oa-tokens.scss
   ============================================================ */
.oa-login {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 480px;
  min-height: 100%;
  background: var(--oa-color-canvas);
  font-family: var(--oa-font-family);
}

/* ---------------- 左：品牌区 ---------------- */
.brand {
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-sizing: border-box;
  gap: var(--oa-space-md);
  padding: var(--oa-space-xxl);
  background: var(--oa-color-surface-1);
  border-right: 1px solid var(--oa-color-hairline);
}
.brand-logo {
  width: 44px;
  height: 44px;
  object-fit: contain;
}
.brand-title {
  margin: 0;
  font: var(--oa-font-display);
  color: var(--oa-color-ink);
}
.brand-sub {
  margin: 0;
  max-width: 460px;
  font: var(--oa-font-body);
  color: var(--oa-color-ink-muted);
}
.brand-list {
  display: flex;
  flex-direction: column;
  gap: var(--oa-space-xs);
  margin: var(--oa-space-xs) 0 0;
  padding: 0;
  list-style: none;
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-muted);

  li {
    position: relative;
    padding-left: var(--oa-space-md);
  }
  /* 6px 品牌色圆点（oa-web 的 brand-list 同款） */
  li::before {
    content: '';
    position: absolute;
    left: 0;
    top: 7px;
    width: 6px;
    height: 6px;
    border-radius: 9999px;
    background: var(--oa-color-primary);
  }
}
.brand-foot {
  margin: var(--oa-space-xl) 0 0;
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}

/* ---------------- 右：表单区 ---------------- */
.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  padding: var(--oa-space-xl) var(--oa-space-lg);
}
.panel-inner {
  width: 100%;
  max-width: 360px;
}
.panel-head {
  margin-bottom: var(--oa-space-lg);
}
.panel-title {
  margin: 0;
  font: var(--oa-font-title-page);
  color: var(--oa-color-ink);
}
.panel-sub {
  margin: var(--oa-space-xxs) 0 0;
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-muted);
}
.remember {
  margin-bottom: var(--oa-space-md);
}
.panel-foot {
  margin: var(--oa-space-lg) 0 0;
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
  text-align: center;
}

/* ---------------- Element 控件对齐令牌 ---------------- */
.login-form {
  .el-input__inner {
    height: 40px;
    line-height: 40px;
    border-radius: var(--oa-radius-md);
    border-color: var(--oa-color-hairline-strong);
  }
  .el-input__icon {
    line-height: 40px;
  }
  .input-icon {
    height: 40px;
    width: 14px;
    margin-left: 2px;
  }
}
.login-code {
  width: 33%;
  height: 40px;
  float: right;

  img {
    cursor: pointer;
    vertical-align: middle;
  }
}
.login-code-img {
  height: 40px;
  border-radius: var(--oa-radius-md);
}

/* ---------------- 窄屏：隐藏品牌区，表单占满 ---------------- */
@media (max-width: 900px) {
  .oa-login {
    grid-template-columns: 1fr;
  }
  .brand {
    display: none;
  }
}
</style>
