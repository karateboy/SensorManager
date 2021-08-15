<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="date"
                format="YYYY-MM-DD"
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
      <div id="sensorFilter" class="sensorFilter mt-2">
        <b-table-simple small fixed>
          <b-tr>
            <b-th>縣市</b-th>
            <b-th>區域劃分</b-th>
            <b-th>類型</b-th>
            <b-th>異常狀態</b-th>
            <b-th></b-th>
          </b-tr>
          <b-tbody>
            <b-tr>
              <b-td
                ><v-select
                  v-model="sensorStatusParam.county"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="countyFilters"
              /></b-td>
              <b-td
                ><v-select
                  v-model="sensorStatusParam.district"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="districtFilters"
              /></b-td>
              <b-td
                ><v-select
                  v-model="sensorStatusParam.sensorType"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="sensorTypes"
              /></b-td>
              <b-td
                ><v-select
                  v-model="errorStatus"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="errorFilters"
                  multiple
              /></b-td>
              <b-td class="text-center"
                ><b-button
                  variant="outline-success"
                  size="sm"
                  @click="exportExcel"
                  ><b-img
                    v-b-tooltip.hover
                    src="../assets/excel_export.svg"
                    title="匯出 Excel"
                    width="24"
                    fluid
                    @click="exportExcel" /></b-button
              ></b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </div>
      <b-table striped hover :fields="fields" :items="errorSensorList">
        <template #cell(inspection)="row">
          <b-select
            v-model="row.item.inspection"
            :options="getInspections(row.item)"
            @change="saveInspection(row.item)"
          />
        </template>
        <template #cell(action)="row">
          <b-select
            v-model="row.item.action"
            :options="getActions(row.item)"
            @change="saveAction(row.item)"
          />
        </template>
      </b-table>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
import { mapActions, mapState, mapGetters, mapMutations } from 'vuex';

import {
  Monitor,
  sensorTypes,
  countyFilters,
  errorFilters as defaultErrorFilter,
  getDistrict,
  TxtStrValue,
  Field,
} from './types';

const excel = require('../libs/excel');
const _ = require('lodash');

interface Sensor extends Monitor {
  date: number;
  status: string;
  effectRate?: number;
  inspection?: string;
  action?: string;
}

interface EffectiveRate {
  _id: string;
  rate: number;
}

interface ErrorAction {
  sensorID: string;
  errorType: string;
  action: string;
}

interface ErrorReport {
  _id: number;
  noErrorCode: Array<string>;
  powerError: Array<string>;
  constant: Array<string>;
  ineffective: Array<EffectiveRate>;
  inspections: Array<ErrorAction>;
  actions: Array<ErrorAction>;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    let range = [
      moment().subtract(6, 'day').startOf('day').valueOf(),
      moment().startOf('day').valueOf(),
    ];

    const errorStatus = Array<string>('lt95');
    let errorFilters = defaultErrorFilter.filter(v => {
      return v.value === 'lt95';
    });
    return {
      display: false,
      errorReports: Array<ErrorReport>(),
      items: [],
      timer: 0,
      errorFilters,
      errorStatus,
      sensorStatusParam: {
        pm25Threshold: '',
        county: '基隆市',
        district: '',
        sensorType: '',
      },
      countyFilters,
      sensorTypes,
      form: {
        range,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),

    districtFilters(): Array<TxtStrValue> {
      return getDistrict(this.sensorStatusParam.county);
    },
    fields() {
      let ret: Array<Field> = [
        {
          key: 'date',
          label: '日期',
          formatter: (date: number) => {
            return moment(date).format('ll');
          },
          sortable: true,
        },
        {
          key: '_id',
          label: '設備序號',
          sortable: true,
        },
        {
          key: 'code',
          label: '代碼',
          sortable: true,
        },
        {
          key: 'shortCode',
          label: '簡碼',
          sortable: true,
        },
        {
          key: 'sensorDetail.roadName',
          label: '路名',
          sortable: true,
        },
        {
          key: 'sensorDetail.locationDesc',
          label: '位置',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
        /*
        {
          key: 'inspection',
          label: '現場檢核',
          sortable: true,
        },
        {
          key: 'action',
          label: '處理情形',
          sortable: true,
        },*/
      ];

      if (this.errorStatus.indexOf('lt95') !== -1) {
        ret.push({
          key: 'effectRate',
          label: '完整率',
          sortable: true,
          formatter: (v: number) => {
            if (v === null) {
              return `N/A`;
            } else {
              let percent = v * 100;
              return `${percent.toFixed(0)}%`;
            }
          },
        });
      }

      return ret;
    },
    errorSensorList(): Array<Sensor> {
      let ret = Array<Sensor>();
      for (let errorReport of this.errorReports) {
        let sensors = this.getErrorSensorList(errorReport);
        ret = ret.concat(sensors);
      }

      return ret;
    },
  },
  async mounted() {
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    setToday() {
      this.form.range = [moment().startOf('day').valueOf(), moment().valueOf()];
    },
    setLast2Days() {
      const last2days = moment().subtract(2, 'day');
      this.form.range = [
        last2days.startOf('day').valueOf(),
        moment().startOf('day').valueOf(),
      ];
    },
    set3DayBefore() {
      const threeDayBefore = moment().subtract(2, 'day');
      this.form.range = [
        threeDayBefore.startOf('day').valueOf(),
        moment().startOf('day').valueOf(),
      ];
    },
    async query() {
      this.display = true;
      this.setLoading({ loading: true });
      await this.getErrorReportList();
      this.setLoading({ loading: false });
    },
    async getErrorReportList(): Promise<void> {
      try {
        const ret = await axios.get(
          `/ErrorReport/${this.form.range[0]}/${this.form.range[1]}`,
        );
        this.errorReports = ret.data as Array<ErrorReport>;
      } catch (err) {
        throw new Error(err);
      }
    },
    getErrorSensorList(errorReport: ErrorReport): Array<Sensor> {
      let date = errorReport._id;
      let ret = Array<Sensor>();
      let updateMap = (
        actionList: Array<ErrorAction>,
        map: Map<string, Map<string, string>>,
      ) => {
        for (let action of actionList) {
          if (!map.has(action.errorType))
            map.set(action.errorType, new Map<string, string>());

          let errorMap = map.get(action.errorType) as Map<string, string>;
          errorMap.set(action.sensorID, action.action);
        }
      };

      let inspectionMap = new Map<string, Map<string, string>>();
      updateMap(errorReport.inspections, inspectionMap);
      let actionMap = new Map<string, Map<string, string>>();
      updateMap(errorReport.actions, actionMap);
      if (this.errorStatus.indexOf('powerError') !== -1) {
        for (const id of errorReport.powerError) {
          let sensor = this.populateSensor(
            date,
            id,
            '充電異常',
            inspectionMap,
            actionMap,
          );
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      if (this.errorStatus.indexOf('constant') !== -1) {
        for (const id of errorReport.constant) {
          let sensor = this.populateSensor(
            date,
            id,
            '定值',
            inspectionMap,
            actionMap,
          );
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      if (this.errorStatus.indexOf('lt95') !== -1) {
        for (const effectRate of errorReport.ineffective) {
          let sensor = this.populateSensor(
            date,
            effectRate._id,
            '完整率<90%',
            inspectionMap,
            actionMap,
          );
          if (sensor !== null) {
            sensor.effectRate = effectRate.rate;
            ret.push(sensor as Sensor);
          }
        }
      }

      if (this.errorStatus.indexOf('noPowerInfo') !== -1) {
        for (const id of errorReport.noErrorCode) {
          let sensor = this.populateSensor(
            date,
            id,
            '無電力資訊',
            inspectionMap,
            actionMap,
          );
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      return ret;
    },
    populateSensor(
      date: number,
      id: string,
      status: string,
      inspectionMap: Map<string, Map<string, string>>,
      actionMap: Map<string, Map<string, string>>,
    ): Sensor | null {
      const m = this.mMap.get(id) as Monitor;
      if (!m || !m.location) return null;

      if (m.county == null) return null;

      if (
        this.sensorStatusParam.county !== '' &&
        m.county !== this.sensorStatusParam.county
      )
        return null;

      if (
        this.sensorStatusParam.district !== '' &&
        m.district !== this.sensorStatusParam.district
      )
        return null;

      if (
        this.sensorStatusParam.sensorType !== '' &&
        m.tags.indexOf(this.sensorStatusParam.sensorType) == -1
      )
        return null;

      let sensor = Object.assign({}, m) as Sensor;

      sensor.status = status;
      sensor.date = date;
      if (inspectionMap.has(status)) {
        let errorMap = inspectionMap.get(status);
        if (errorMap !== undefined) {
          sensor.inspection = errorMap.get(sensor._id);
        }
      }
      if (actionMap.has(status)) {
        let errorMap = actionMap.get(status);
        if (errorMap != undefined) {
          sensor.action = errorMap.get(sensor._id);
        }
      }
      return sensor;
    },
    exportExcel() {
      const title = this.fields.map(e => e.label);
      const key = this.fields.map(e => e.key);
      for (let entry of this.errorSensorList) {
        let e = entry as any;
        for (let k of key) {
          e[k] = _.get(entry, k);
        }
      }
      const start = new Date(this.form.range[0]);
      start.toLocaleDateString();
      let month = String(start.getMonth() + 1).padStart(2, '0');
      let day = String(start.getDate()).padStart(2, '0');
      const end = new Date(this.form.range[1]);
      let monthEnd = String(end.getMonth() + 1).padStart(2, '0');
      let dayEnd = String(end.getDate()).padStart(2, '0');
      const params = {
        title,
        key,
        data: this.errorSensorList,
        autoWidth: true,
        filename: `${start.getFullYear()}${month}${day}_${monthEnd}${dayEnd}感測器異常列表`,
      };
      excel.export_array_to_excel(params);
    },
    async saveInspection(item: Sensor) {
      try {
        if (item.inspection) {
          let action: ErrorAction = {
            sensorID: item._id,
            errorType: item.status,
            action: item.inspection as string,
          };
          await axios.post(`/ErrorReport/inspection/${item.date}`, action);
        }
      } catch (err) {
        throw new Error(err);
      }
    },
    async saveAction(item: Sensor) {
      try {
        if (item.action) {
          let action: ErrorAction = {
            sensorID: item._id,
            errorType: item.status,
            action: item.action as string,
          };
          await axios.post(`/ErrorReport/action/${item.date}`, action);
        }
      } catch (err) {
        throw new Error(err);
      }
    },
    getInspections(sensor: Sensor): Array<string> {
      switch (sensor.status) {
        case '充電異常':
          return ['路燈沒電', '斷路器跳開', '設備異常', '其他'];

        case '定值':
          return ['環境因素', '採樣口堵塞', '設備異常', '其他'];

        case '通訊中斷':
          return ['路燈沒電', '斷路器跳開', '設備異常', '其他'];
      }
      return [];
    },
    getActions(sensor: Sensor): Array<string> {
      switch (sensor.status) {
        case '充電異常':
        case '通訊中斷':
          return [
            '重開機',
            '更換主機',
            '通知路燈管理單位',
            '重啟斷路器',
            '其他',
          ];

        case '定值':
          return ['重開機', '更換主機', '設備清潔', '待觀察', '其他'];
      }
      return [];
    },
  },
});
</script>
<style></style>
