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
        <b-form-group
          label="測點群組"
          label-for="monitorGroup"
          label-cols-md="3"
        >
          <v-select
            id="monitorGroup"
            v-model="form.monitorGroupID"
            label="_id"
            :reduce="mg => mg._id"
            :options="filteredMonitorGroupList"
          />
        </b-form-group>
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
              @click="query"
            >
              查詢
            </b-button>

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
import { MonitorGroup, Quartile, QuartileReport } from './types';

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
        monitorGroupID: '',
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
  async mounted() {
    await this.getMonitorGroups();
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
      const url = `/OutstandingReport/JSON/${this.form.monitorGroupID}/${this.form.date}`;
      const res = await axios.get(url);
      const ret: Array<QuartileReport> = res.data;

      const avgQr = ret[0];
      const avg = avgQr.quartile.q2;

      for (let i = 1; i < ret.length; i++) {
        const qr = ret[i];
        const mean = qr.quartile.q2;
        if (mean <= avgQr.quartile.q3 && mean >= avgQr.quartile.q1)
          ret[i].away = false;
        else ret[i].away = true;
      }

      const categories = ret.map(qr => {
        if (qr.away == true) return `${qr.name.slice(-4)}(離群)`;
        else return `${qr.name.slice(-4)}`;
      });
      const data = ret.map(qr => {
        const q = qr.quartile;
        return [
          q.min,
          q.q1,
          q.q2,
          q.q3,
          q.max,
        ] as highcharts.XrangePointOptionsObject;
      });

      this.setLoading({ loading: false });

      const series: Array<highcharts.SeriesBoxplotOptions> = [
        {
          type: 'boxplot',
          name: '感測器',
          data,
          fillColor: {
            linearGradient: { x1: 0, x2: 0, y1: 0, y2: 1 },
            stops: [
              [0, '#003399'], // start
              [0.5, '#ffffff'], // middle
              [1, '#3366AA'], // end
            ],
          },
          tooltip: {
            headerFormat: '<em>感測器 {point.key}</em><br/>',
            pointFormat:
              '<span style="color:{point.color}">●</span> <b> {series.name}</b><br/>最大值: {point.high}<br/>第三四分位: {point.q3}<br/>中位數: {point.median}<br/>第一四分位: {point.q1}<br/>最小值: {point.low}<br/>',
          },
        },
      ];

      let chartOption: highcharts.Options = {
        chart: {
          type: 'boxplot',
        },
        title: {
          text: `${this.form.monitorGroupID} 離群分析報表`,
        },
        legend: {
          enabled: false,
        },
        xAxis: {
          categories,
          title: {
            text: '代碼',
          },
        },
        yAxis: {
          title: {
            text: 'PM2.5測值',
          },
          plotLines: [
            {
              value: avg,
              color: 'red',
              width: 1,
              label: {
                text: '中位數平均',
                align: 'right',
                style: {
                  color: 'red',
                },
              },
              zIndex: 100,
            },
          ],
        },
        credits: {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        },
        series,
      };
      highcharts.chart('chart_container', chartOption);
    },
    async getMonitorGroups() {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
  },
});
</script>

<style></style>
