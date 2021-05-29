import axios from 'axios';

export default {
  namespaced: true,
  state: {
    monitors: [],
  },
  getters: {
    mMap(state) {
      const map = new Map();
      for (const m of state.monitors) {
        map.set(m._id, m);
      }
      return map;
    },
  },
  mutations: {
    setMonitors(state, val) {
      state.monitors = val;
    },
  },
  actions: {
    async fetchMonitors({ commit }) {
      try {
        const res = await axios.get('/Monitors');
        if (res.status === 200) {
          commit('setMonitors', res.data);
        }
      } catch (err) {
        throw new Error(err);
      }
    },
  },
};
