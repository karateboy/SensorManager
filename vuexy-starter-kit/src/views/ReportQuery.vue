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
                v-model="form.monitor"
                label="desc"
                :reduce="mt => mt._id"
                :options="filteredMonitors"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="報表種類"
              label-for="reportType"
              label-cols-md="3"
            >
              <v-select
                id="reportType"
                v-model="form.reportType"
                label="txt"
                :reduce="dt => dt.id"
                :options="reportTypes"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢日期"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.date"
                :type="pickerType"
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
      <div>
        <b-table
          responsive
          striped
          hover
          :fields="columns"
          :items="rows"
          bordered
        >
          <template #custom-foot>
            <b-tr v-for="stat in statRows" :key="stat.name">
              <b-th>{{ stat.name }}</b-th>
              <th v-for="(cell, i) in stat.cellData" :key="i">
                {{ cell.v }}
              </th>
            </b-tr>
          </template>
        </b-table>
      </div>
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
import moment from 'moment';
import axios from 'axios';
import { mapState, mapActions, mapMutations } from 'vuex';
import { MonitorGroup, countyFilters, Monitor } from './types';

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    let monitorGroup: MonitorGroup | undefined;
    let monitor: string | undefined;
    return {
      county: '',
      countyFilters,
      monitorGroup,
      display: false,
      reportTypes: [
        { id: 'daily', txt: '日報' },
        { id: 'monthly', txt: '月報' },
      ],
      columns: Array<any>(),
      statRows: Array<any>(),
      rows: Array<any>(),
      form: {
        monitor,
        date,
        reportType: 'daily',
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors', 'monitorGroupList']),
    pickerType(): string {
      if (this.form.reportType === 'daily') return 'date';
      return 'month';
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
    filteredMonitors(): Array<Monitor> {
      return this.monitors
        .filter((monitor: Monitor) => {
          if (this.county === '') return true;
          else return monitor.county === this.county;
        })
        .filter((monitor: Monitor) => {
          if (typeof this.monitorGroup === 'undefined') return true;
          else {
            let mg: MonitorGroup = this.monitorGroup as MonitorGroup;
            return mg.member.indexOf(monitor._id) !== -1;
          }
        });
    },
    canQuery(): boolean {
      if (this.form.monitor && this.form.reportType && this.form.date)
        return true;
      else return false;
    },
  },
  watch: {
    county() {
      this.form.monitor = undefined;
      this.monitorGroup = undefined;
    },
    monitorGroup() {
      this.form.monitor = undefined;
    },
  },
  async mounted() {
    await this.getMonitorGroups();
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors', 'getMonitorGroups']),
    ...mapMutations(['setLoading']),
    async query() {
      const url = `/monitorReport/${this.form.reportType}/${this.form.monitor}/${this.form.date}`;
      this.setLoading({ loading: true });
      try {
        const res = await axios.get(url);
        this.display = true;
        this.handleReport(res.data);
      } finally {
        this.setLoading({ loading: false });
      }
    },
    handleReport(report: any) {
      this.columns.splice(0, this.columns.length);
      if (this.form.reportType === 'daily') {
        this.columns.push({
          key: 'time',
          label: '時間',
          sortable: true,
        });
      } else {
        this.columns.push({
          key: 'time',
          label: '日期',
          sortable: true,
        });
      }
      for (let i = 0; i < report.columnNames.length; i++) {
        this.columns.push({
          key: `cellData[${i}].v`,
          label: `${report.columnNames[i]}`,
          sortable: true,
        });
      }
      for (const row of report.hourRows) {
        row.time =
          this.form.reportType === 'daily'
            ? moment(row.date).format('HH:mm')
            : moment(row.date).format('MM/DD');
      }
      this.rows = report.hourRows;
      this.statRows = report.statRows;
    },
  },
});
</script>

<style></style>
