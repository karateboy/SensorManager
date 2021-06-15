import axios from 'axios';

export default {
  namespaced: true,
  state: {
    userInfo: {
      _id: '',
      name: '',
      phone: '',
      isAdmin: false,
      group: '',
      monitorTypeOfInterest: [],
    },
  },
  getters: {},
  actions: {
    async getUserInfo({ commit }) {
      try {
        const res = await axios.get('/User');
        if (res.status === 200) {
          commit('setUserInfo', res.data);
          return res.data;
        }
      } catch (err) {
        throw new Error(err);
      }
    },
  },
  mutations: {
    setUserInfo(state, val) {
      state.userInfo._id = val._id;
      state.userInfo.name = val.name;
      state.userInfo.isAdmin = val.isAdmin;
      state.userInfo.group = val.group;
      state.userInfo.monitorTypeOfInterest = val.monitorTypeOfInterest;
    },
  },
};
