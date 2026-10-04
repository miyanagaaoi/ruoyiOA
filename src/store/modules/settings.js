import defaultSettings from '@/settings'
import { useDynamicTitle } from '@/utils/dynamicTitle'

const { sideTheme, showSettings, navType, tagsView, tagsIcon, fixedHeader, sidebarLogo, dynamicTitle, footerVisible, footerContent } = defaultSettings

const storageSetting = JSON.parse(localStorage.getItem('layout-setting')) || ''

/**
 * 主题色：集团 OA 品牌蓝。
 *
 * ⚠ 迁移逻辑不可省。ThemePicker 在 created() 里只要发现 `settings.theme !== ORIGINAL_THEME`
 * 就会主动换色，而 ORIGINAL_THEME 现在等于品牌蓝。老用户的 localStorage('layout-setting')
 * 里存的是旧的若依蓝 #409EFF（或 Element 默认的 #1890ff），若不迁移，一进页面就会被
 * 换回若依蓝，表现为"改了源码还是蓝的"。
 */
const BRAND_THEME = '#1f5ae0'
const LEGACY_THEMES = ['#409eff', '#1890ff'] // 一律小写比较，避免大小写变体漏判
const storedTheme = storageSetting.theme
const isLegacy = typeof storedTheme === 'string' && LEGACY_THEMES.indexOf(storedTheme.toLowerCase()) >= 0
const initialTheme = (!storedTheme || isLegacy) ? BRAND_THEME : storedTheme

const state = {
  title: '',
  theme: initialTheme,
  sideTheme: storageSetting.sideTheme || sideTheme,
  showSettings: showSettings,
  navType: storageSetting.navType === undefined ? navType : storageSetting.navType,
  tagsView: storageSetting.tagsView === undefined ? tagsView : storageSetting.tagsView,
  tagsIcon: storageSetting.tagsIcon === undefined ? tagsIcon : storageSetting.tagsIcon,
  fixedHeader: storageSetting.fixedHeader === undefined ? fixedHeader : storageSetting.fixedHeader,
  sidebarLogo: storageSetting.sidebarLogo === undefined ? sidebarLogo : storageSetting.sidebarLogo,
  dynamicTitle: storageSetting.dynamicTitle === undefined ? dynamicTitle : storageSetting.dynamicTitle,
  footerVisible: storageSetting.footerVisible === undefined ? footerVisible : storageSetting.footerVisible,
  footerContent: footerContent
}
const mutations = {
  CHANGE_SETTING: (state, { key, value }) => {
    if (state.hasOwnProperty(key)) {
      state[key] = value
    }
  },
  SET_TITLE: (state, title) => {
    state.title = title
  }
}

const actions = {
  // 修改布局设置
  changeSetting({ commit }, data) {
    commit('CHANGE_SETTING', data)
  },
  // 设置网页标题
  setTitle({ commit }, title) {
    commit('SET_TITLE', title)
    useDynamicTitle()
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}

