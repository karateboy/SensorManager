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
            multiple
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
          <b-col cols="12">
            <b-form-group label="Outlier" label-for="outlier" label-cols-md="3">
              <b-form-checkbox id="outlier" v-model="showOutlier"
                >顯示</b-form-checkbox
              >
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
import { mapMutations } from 'vuex';
import { MonitorGroup, QuartileReport } from './types';

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
        monitorGroupID: [],
      },
      display: false,
      monitorGroupList,
      showOutlier: false,
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
      const url = `/OutstandingReport/JSON/${this.form.monitorGroupID.join(
        ':',
      )}/${this.form.date}`;
      const res = await axios.get(url);
      const ret: Array<QuartileReport> = res.data;

      if (ret.length == 0) {
        this.setLoading({ loading: false });
        await this.$bvModal
          .msgBoxOk('無感測器資料, 請確認是否已經匯入', {
            title: '確認',
            size: 'sm',
            buttonSize: 'sm',
            okVariant: 'success',
            headerClass: 'p-2 border-bottom-0',
            footerClass: 'p-2 border-top-0',
            centered: true,
          })
          .catch(err => {
            // An error occurred
          });
        return;
      }

      const avgQr = ret[0];
      const avgMean = avgQr.quartile.q2;

      for (let i = 1; i < ret.length; i++) {
        const qr = ret[i];
        if (avgMean > qr.quartile.q3 || avgMean < qr.quartile.q1)
          ret[i].away = true;
        else ret[i].away = false;
      }

      const categories = ret.map(qr => {
        if (qr.away == true) return `${qr.name.slice(-4)}(離群)`;
        else return `${qr.name.slice(-4)}`;
      });
      const data = ret.map(qr => {
        const q = qr.quartile;
        let name = `${qr.name.slice(-4)}`;
        if (qr.away == true) name = `${qr.name.slice(-4)}(離群)`;
        let color = qr.away ? 'red' : 'black';
        return {
          low: q.min,
          q1: q.q1,
          median: q.q2,
          q3: q.q3,
          high: q.max,
          name,
          color,
        } as highcharts.PointOptionsObject;
      });

      const outlier = ret
        .map((qr, x) => {
          let name = `${qr.name.slice(-4)}`;
          return qr.outlier.map(y => {
            return {
              name,
              x,
              y,
            } as highcharts.PointOptionsObject;
          });
        })
        .flat();

      const outlierSeries = {
        name: 'Farout',
        type: 'scatter',
        data: outlier,
        marker: {
          fillColor: 'black',
          lineWidth: 2,
        },
        tooltip: {
          pointFormat: '{point.name}: {point.y}',
          valueDecimals: 2,
        },
      } as highcharts.SeriesScatterOptions;

      this.setLoading({ loading: false });

      const series: Array<
        highcharts.SeriesBoxplotOptions | highcharts.SeriesScatterOptions
      > = [
        {
          type: 'boxplot',
          name: '感測器',
          data,
          colorByPoint: true,
          tooltip: {
            headerFormat: '<em>感測器 {point.key}</em><br/>',
            pointFormat:
              '<span style="color:{point.color}">●</span> <b> {series.name}</b><br/>上限: {point.high}<br/>第三四分位: {point.q3}<br/>中位數: {point.median}<br/>第一四分位: {point.q1}<br/>下限: {point.low}<br/>',
            valueDecimals: 2,
          },
        },
      ];
      if (this.showOutlier) series.unshift(outlierSeries);

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
              value: avgMean,
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
