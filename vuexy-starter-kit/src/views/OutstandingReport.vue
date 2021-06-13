<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="縣市" label-for="county" label-cols-md="3">
              <v-select
                id="monitorType"
                v-model="form.county"
                label="txt"
                :reduce="county => county.value"
                :options="counties"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="報表月份"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.date"
                type="month"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="download"
            >
              下載
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
    <b-card v-show="display">
      <div id="chart_container" />
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
import moment from 'moment';
import axios from 'axios';
import highcharts from 'highcharts';
import { mapState, mapActions, mapMutations } from 'vuex';
import { MonitorGroup } from './types';

const Ripple = require('vue-ripple-directive');

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    let monitorGroupList = Array<MonitorGroup>();
    return {
      counties: [
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
      ],
      form: {
        date,
        county: '基隆市',
      },
      display: false,
      monitorGroupList,
    };
  },
  computed: {
    filteredMonitorGroupList(): Array<MonitorGroup> {
      if (this.form.county === '') return this.monitorGroupList;
      else {
        return this.monitorGroupList.filter(
          (value: MonitorGroup, index: number) => {
            let prefix = '';
            switch (this.form.county) {
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
  },
  methods: {
    ...mapMutations(['setLoading']),
    async download() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '';
      const url = `${baseUrl}OutstandingReport/${this.form.county}/${this.form.date}`;
      window.open(url);
    },
    async query() {
      this.setLoading({ loading: true });
      this.display = true;
      const url = `/JSON/OutstandingReport/${this.form.county}/${this.form.date}`;
      const res = await axios.get(url);
      const ret = res.data;

      this.setLoading({ loading: false });
      highcharts.chart('chart_container', ret);
    },
    async getMonitorGroups() {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
  },
});
</script>

<style></style>
