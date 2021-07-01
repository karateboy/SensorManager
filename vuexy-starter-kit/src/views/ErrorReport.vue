<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
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
                type="date"
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
                    src="../assets/excel_export.svg"
                    width="24"
                    fluid
                    @click="exportExcel" /></b-button
              ></b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </div>
      <b-table striped hover :fields="fields" :items="errorSensorList" />
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
  errorFilters,
  getDistrict,
  TxtStrValue,
} from './types';

const excel = require('../libs/excel');
const _ = require('lodash');

interface Sensor extends Monitor {
  status: string;
  effectRate?: number;
}

interface EffectRate {
  _id: string;
  rate: number;
}

interface ErrorReport {
  noErrorCode: Array<string>;
  powerError: Array<string>;
  constant: Array<string>;
  inEffect: Array<EffectRate>;
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
    let errorReport: ErrorReport = {
      noErrorCode: [],
      powerError: [],
      constant: [],
      inEffect: [],
    };
    const errorStatus = Array<string>('constant');
    return {
      display: false,
      errorReport,
      fields: [
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
          key: 'location[0]',
          label: '經度',
          sortable: true,
        },
        {
          key: 'location[1]',
          label: '緯度',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ],
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
        date,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    errorSensorList(): Array<Sensor> {
      let ret = Array<Sensor>();

      if (this.errorStatus.indexOf('powerError') !== -1) {
        for (const id of this.errorReport.powerError) {
          let sensor = this.populateSensor(id, '電力異常');
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      if (this.errorStatus.indexOf('constant') !== -1) {
        for (const id of this.errorReport.constant) {
          let sensor = this.populateSensor(id, '定值');
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      if (this.errorStatus.indexOf('lt95') !== -1) {
        for (const effectRate of this.errorReport.inEffect) {
          let sensor = this.populateSensor(effectRate._id, '完整率<90%');
          if (sensor !== null) {
            sensor.effectRate = effectRate.rate;
            ret.push(sensor as Sensor);
          }
        }
      }

      if (this.errorStatus.indexOf('noPowerInfo') !== -1) {
        for (const id of this.errorReport.noErrorCode) {
          let sensor = this.populateSensor(id, '無電力資訊');
          if (sensor !== null) ret.push(sensor as Sensor);
        }
      }

      return ret;
    },
    districtFilters(): Array<TxtStrValue> {
      return getDistrict(this.sensorStatusParam.county);
    },
  },
  async mounted() {
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.display = true;
      this.setLoading({ loading: true });
      await this.getPowerErrorList();
      this.setLoading({ loading: false });
    },
    async getPowerErrorList(): Promise<void> {
      const ret = await axios.get(`/ErrorReport/${this.form.date}`);

      let reports = ret.data as Array<ErrorReport>;
      if (reports.length === 1) {
        this.errorReport.noErrorCode = reports[0].noErrorCode;
        this.errorReport.powerError = reports[0].powerError;
        this.errorReport.constant = reports[0].constant;
        this.errorReport.inEffect = reports[0].inEffect;
      }
    },
    populateSensor(id: string, status: string): Sensor | null {
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
      const params = {
        title,
        key,
        data: this.errorSensorList,
        autoWidth: true,
        filename: '感測器異常列表',
      };
      excel.export_array_to_excel(params);
    },
  },
});
</script>
<style></style>
