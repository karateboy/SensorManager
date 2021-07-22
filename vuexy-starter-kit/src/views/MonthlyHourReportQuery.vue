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
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="form.monitorType"
                label="desp"
                :reduce="mt => mt._id"
                :options="monitorTypes"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢月份"
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
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
import { mapState, mapActions, mapMutations } from 'vuex';
import {
  MonitorGroup,
  countyFilters,
  Monitor,
  MonthlyHourReport,
  StatRow,
} from './types';

interface MonthlyHourReport2 extends MonthlyHourReport {
  dateStr?: string;
}
export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    let monitorGroup: MonitorGroup | undefined;
    let monitor: string | undefined;
    let monitorType: string | undefined;
    return {
      display: false,
      columns: Array<any>(),
      statRows: Array<StatRow>(),
      rows: Array<any>(),
      county: '',
      countyFilters,
      monitorGroup,
      form: {
        monitor,
        date,
        monitorType,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors', 'monitorGroupList']),
    ...mapState('monitorTypes', ['monitorTypes']),
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
      if (this.form.monitor && this.form.date) return true;
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
    await this.fetchMonitorTypes();
    if (this.monitorTypes.length !== 0) {
      this.form.monitorType = 'PM25';
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors', 'getMonitorGroups']),
    ...mapMutations(['setLoading']),
    async query() {
      const url = `/MonthlyHourReport/${this.form.monitor}/${this.form.monitorType}/${this.form.date}`;
      this.setLoading({ loading: true });
      try {
        const res = await axios.get(url);
        this.display = true;
        this.handleReport(res.data);
      } finally {
        this.setLoading({ loading: false });
      }
    },
    handleReport(report: MonthlyHourReport2) {
      this.columns.splice(0, this.columns.length);

      this.columns.push({
        key: 'dateStr',
        label: '日\\時間',
        sortable: true,
      });

      for (let i = 0; i < report.columnNames.length; i++) {
        this.columns.push({
          key: `cellData[${i}].v`,
          label: `${report.columnNames[i]}`,
          sortable: true,
          stickyColumn: true,
        });
      }
      for (const row of report.rows) {
        row.dateStr = moment(row.date).format('MM/DD');
      }
      this.rows = report.rows;
      this.statRows = report.statRows;
    },
  },
});
</script>

<style></style>
