<template>
  <div>
    <b-alert variant="primary" dismissible show fade
      ><h1>趨勢圖可用滑鼠拖曳放大區域</h1></b-alert
    >
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
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="setToday"
                >今天</b-button
              >
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="setLast2Days"
                >前兩天</b-button
              >
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="set3DayBefore"
                >前三天</b-button
              >
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              :disabled="!canQuery"
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
$namespace: 'mx';
$default-color: #000;
$primary-color: #1284e7;

@import 'vue2-datepicker/scss/index.scss';
</style>
<script lang="ts">
import Vue, { PropType } from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapActions, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import { MonitorGroup, countyFilters } from './types';
import useAppConfig from '../@core/app-config/useAppConfig';
export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  props: {
    queryMonitors: {
      type: Array as PropType<Array<string>>,
      default: () => [],
    },
    queryMonitorTypes: {
      type: Array as PropType<Array<string>>,
      default: () => [],
    },
    queryRange: {
      type: Array as PropType<Array<number>>,
      default: () => [],
    },
  },
  data() {
    let range = Array<number>();
    if (this.queryRange.length === 2) {
      range.push(this.queryRange[0]);
      range.push(this.queryRange[1]);
    } else {
      range = [
        moment().subtract(1, 'days').startOf('hour').valueOf(),
        moment().startOf('hour').valueOf(),
      ];
    }

    let monitorGroup: MonitorGroup | undefined = undefined;
    let monitors = Array<string>();
    if (this.queryMonitors.length !== 0) {
      for (let id of this.queryMonitors) monitors.push(id);
    }
    let monitorTypes = Array<string>();
    if (this.queryMonitorTypes.length !== 0) {
      for (let mt of this.queryMonitorTypes) monitorTypes.push(mt);
    }

    let form = {
      monitors,
      monitorTypes,
      reportUnit: 'Hour',
      statusFilter: 'all',
      chartType: 'line',
      range,
    };
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
      monitorGroup,
      countyFilters,
      county: '',
      form,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors', 'monitorGroupList']),
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
    canQuery(): boolean {
      return (
        this.form.monitors.length !== 0 && this.form.monitorTypes.length !== 0
      );
    },
  },
  watch: {
    monitorGroup(newValue: MonitorGroup) {
      this.form.monitors = newValue.member;
    },
  },
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.getMonitorGroups();
    await this.fetchMonitorTypes();
    await this.fetchMonitors();

    if (this.queryMonitors.length !== 0) {
      this.query();
    } else {
      if (this.monitorTypes.length !== 0) this.form.monitorTypes.push('PM25');
      if (this.monitors.length !== 0)
        this.form.monitors.push(this.monitors[0]._id);
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors', 'getMonitorGroups']),
    ...mapMutations(['setLoading']),
    setToday() {
      this.form.range = [moment().startOf('day').valueOf(), moment().valueOf()];
    },
    setLast2Days() {
      const last2days = moment().subtract(2, 'day');
      this.form.range = [
        last2days.startOf('day').valueOf(),
        moment().valueOf(),
      ];
    },
    set3DayBefore() {
      const threeDayBefore = moment().subtract(3, 'day');
      this.form.range = [
        threeDayBefore.startOf('day').valueOf(),
        moment().valueOf(),
      ];
    },
    async query(): Promise<void> {
      this.setLoading({ loading: true });
      this.display = true;
      const monitors = this.form.monitors.join(':');
      const url = `/HistoryTrend/${monitors}/${this.form.monitorTypes.join(
        ':',
      )}/${this.form.reportUnit}/${this.form.statusFilter}/${
        this.form.range[0]
      }/${this.form.range[1]}`;
      try {
        const res = await axios.get(url);
        const ret = res.data as highcharts.Options;

        if (this.form.chartType !== 'boxplot') {
          const panning: Highcharts.ChartPanningOptions = {};
          ret.chart = {
            type: this.form.chartType,
            zoomType: 'xy',
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
      } catch (err) {
        throw new Error(`${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },
  },
});
</script>

<style></style>
