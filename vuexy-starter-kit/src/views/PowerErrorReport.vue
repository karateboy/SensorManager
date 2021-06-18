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

interface Sensor {
  _id: string;
  road: string;
  status: string;
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
    let powerErrorList = Array<string>();
    return {
      display: false,
      powerErrorList,
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
          key: 'road',
          label: '路名',
          sortable: true,
        },
        {
          key: 'locationDesc',
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

      for (const id of this.powerErrorList) {
        const m = this.mMap.get(id);
        if (!m || !m.location) continue;

        let sensor = Object.assign({ status: '電力異常' }, m);
        if (m.sensorDetail) {
          sensor.locationDesc = m.sensorDetail.locationDesc;
          sensor.road = m.sensorDetail.roadName;
        }

        ret.push(sensor);
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
    async query() {
      this.display = true;
      this.setLoading({ loading: true });
      await this.getPowerErrorList();
      this.setLoading({ loading: false });
    },
    async getPowerErrorList(): Promise<void> {
      const params = {
        county: '',
        district: '',
        sensorType: '',
      };
      const ret = await axios.get(`/PowerErrorReport/${this.form.date}`);

      this.powerErrorList = ret.data;
    },
  },
});
</script>
<style></style>
