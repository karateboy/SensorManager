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
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="form.monitorTypes"
                label="desp"
                :reduce="mt => mt._id"
                :options="monitorTypes"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="時間單位"
              label-for="reportUnit"
              label-cols-md="3"
            >
              <v-select
                id="reportUnit"
                v-model="form.reportUnit"
                label="txt"
                :reduce="dt => dt.id"
                :options="reportUnits"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="圖表類型"
              label-for="chartType"
              label-cols-md="3"
            >
              <v-select
                id="chartType"
                v-model="form.chartType"
                label="desc"
                :reduce="ct => ct.type"
                :options="chartTypes"
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
                format="YYYY-MM-DD HH:mm"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
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
const Ripple = require('vue-ripple-directive');
import { mapState, mapActions, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';
import highcharts from 'highcharts';
import { MonitorGroup } from './types';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
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
      reportUnits: [
        { txt: '分', id: 'Min' },
        { txt: '小時', id: 'Hour' },
      ],
      reportUnit: 'Hour',
      display: false,
      chartTypes: [
        {
          type: 'line',
          desc: '折線圖',
        },
        {
          type: 'spline',
          desc: '曲線圖',
        },
        {
          type: 'area',
          desc: '面積圖',
        },
        {
          type: 'areaspline',
          desc: '曲線面積圖',
        },
        {
          type: 'column',
          desc: '柱狀圖',
        },
        {
          type: 'scatter',
          desc: '點圖',
        },
      ],
      monitorGroupList: Array<MonitorGroup>(),
      monitorGroup,
      countyFilters,
      county: '',
      form: {
        monitors: Array<string>(),
        monitorTypes: Array<string>(),
        reportUnit: 'Min',
        statusFilter: 'all',
        chartType: 'line',
        range,
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
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
    await this.getMonitorGroups();
    await this.fetchMonitorTypes();
    await this.fetchMonitors();

    if (this.monitorTypes.length !== 0) {
      this.form.monitorTypes.push('PM25');
    }

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id);
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query(): Promise<void> {
      this.setLoading({ loading: true });
      this.display = true;
      const monitors = this.form.monitors.join(':');
      const url = `/HistoryTrend/${monitors}/${this.form.monitorTypes.join(
        ':',
      )}/${this.form.reportUnit}/${this.form.statusFilter}/${
        this.form.range[0]
      }/${this.form.range[1]}`;
      const res = await axios.get(url);
      const ret = res.data as highcharts.Options;

      this.setLoading({ loading: false });
      if (this.form.chartType !== 'boxplot') {
        const panning: Highcharts.ChartPanningOptions = {};
        ret.chart = {
          type: this.form.chartType,
          zoomType: 'x',
          panning,
          panKey: 'shift',
          alignTicks: false,
        };

        const pointFormatter = function pointFormatter(this: any) {
          const d = new Date(this.x);
          return `${d.toLocaleString()}:${Math.round(this.y)}度`;
        };

        ret.colors = [
          '#7CB5EC',
          '#434348',
          '#90ED7D',
          '#F7A35C',
          '#8085E9',
          '#F15C80',
          '#E4D354',
          '#2B908F',
          '#FB9FA8',
          '#91E8E1',
          '#7CB5EC',
          '#80C535',
          '#969696',
        ];

        ret.tooltip = { valueDecimals: 2 };
        ret.legend = { enabled: true };
        if (this.form.monitorTypes.indexOf('BATTERY') !== -1) {
          ret.legend.title = {
            text: '1=電池、4=市電',
          };
        }
        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        };
        let xAxis = ret.xAxis as highcharts.XAxisOptions;
        xAxis.type = 'datetime';
        xAxis.dateTimeLabelFormats = {
          day: '%b%e日',
          week: '%b%e日',
          month: '%y年%b',
        };

        ret.plotOptions = {
          scatter: {
            tooltip: {
              pointFormatter,
            },
          },
        };
        ret.time = {
          timezoneOffset: -480,
        };
      }
      highcharts.chart('chart_container', ret);
    },
    async getMonitorGroups(): Promise<void> {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
  },
});
</script>

<style></style>
