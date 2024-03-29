<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="縣市" label-for="county" label-cols-md="3">
              <v-select
                id="county"
                v-model="county"
                label="txt"
                :reduce="county => county.value"
                :options="countyFilters"
              />
            </b-form-group>
            <b-form-group
              label="測點群組"
              label-for="monitorGroup"
              label-cols-md="3"
            >
              <v-select
                id="monitorGroup"
                v-model="monitorGroup"
                label="_id"
                :reduce="mg => mg"
                :options="filteredMonitorGroupList"
              />
            </b-form-group>
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitors"
                label="desc"
                :reduce="mt => mt._id"
                :options="monitors"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料來源"
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="form.dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="dataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="datetime"
                format="YYYY-MM-DD"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="!canRecalculate"
              @click="recalculate"
            >
              重新計算
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              variant="outline-secondary"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import vSelect from 'vue-select';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapGetters, mapActions, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';
import { MonitorGroup } from './types';

export default Vue.extend({
  components: {
    vSelect,
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().subtract(1, 'days').hour(0).minute(0).millisecond(0).valueOf(),
      moment().hour(23).minute(59).minute(0).millisecond(0).valueOf(),
    ];
    let monitorGroup: MonitorGroup | undefined = undefined;
    const countyFilters = [
      {
        txt: '不限',
        value: '',
      },
      {
        txt: '基隆',
        value: '基隆市',
      },
      {
        txt: '屏東',
        value: '屏東縣',
      },
      {
        txt: '宜蘭',
        value: '宜蘭縣',
      },
    ];
    return {
      dataTypes: [{ txt: '小時資料', id: 'hour' }],
      monitorGroupList: Array<MonitorGroup>(),
      monitorGroup,
      countyFilters,
      county: '',
      form: {
        monitors: Array<any>(),
        monitorTypes: [],
        dataType: 'hour',
        range,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    canRecalculate(): boolean {
      return this.form.monitors.length !== 0;
    },
    filteredMonitorGroupList(): Array<MonitorGroup> {
      if (this.county === '') return this.monitorGroupList;
      else {
        return this.monitorGroupList.filter(
          (value: MonitorGroup, index: number) => {
            let prefix = '';
            switch (this.county) {
              case '基隆市':
                prefix = 'K';
                break;
              case '屏東縣':
                prefix = 'P';
                break;
              case '宜蘭縣':
                prefix = 'Y';
                break;
            }

            return value._id.startsWith(prefix);
          },
        );
      }
    },
    filteredMonitors(): Array<any> {
      if (this.county === '') return this.monitors;
      return this.monitors.filter((monitor: any, index: number) => {
        return monitor.county === this.county;
      });
    },
  },
  watch: {
    monitorGroup(newValue: MonitorGroup) {
      this.form.monitors = newValue.member;
    },
  },
  async mounted() {
    await this.fetchMonitors();
    await this.getMonitorGroups();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),

    async recalculate() {
      const monitors = this.form.monitors.join(':');
      const url = `/Recalculate/${monitors}/${this.form.range[0]}/${this.form.range[1]}`;

      try {
        const res = await axios.get(url);
        if (res.data.ok) {
          this.$bvModal.msgBoxOk('開始重新計算小時值');
        }
      } catch (err) {
        throw new Error('failed to recalculate hour');
      }
    },
    async getMonitorGroups() {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
  },
});
</script>

<style></style>
