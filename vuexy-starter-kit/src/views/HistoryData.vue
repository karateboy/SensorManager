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
                :options="filteredMonitors"
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
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="form.dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="dataTypes"
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
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="downloadCSV"
                >下載CSV檔案</b-button
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
    <b-card v-show="display" :title="resultTitle">
      <b-table
        hover
        :fields="columns"
        :items="rows"
        show-empty
        :per-page="15"
        :current-page="currentPage"
        responsive
        sticky-header="800px"
      >
        <template #thead-top>
          <b-tr>
            <b-th></b-th>
            <b-th
              v-for="mt in form.monitorTypes"
              :key="mt"
              :colspan="form.monitors.length"
              class="text-center"
              style="text-transform: none"
              >{{ getMtDesc(mt) }}</b-th
            >
          </b-tr>
        </template>
      </b-table>
      <b-pagination
        v-model="currentPage"
        :total-rows="rows.length"
        :per-page="15"
        first-text="⏮"
        prev-text="⏪"
        next-text="⏩"
        last-text="⏭"
        class="mt-4"
      ></b-pagination>
    </b-card>
  </div>
</template>
<style lang="scss">
$namespace: 'xmx'; // change the 'mx' to 'xmx'. then <date-picker prefix-class="xmx" />

$default-color: #555;
$primary-color: #1284e7;

@import '~vue2-datepicker/scss/index.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapGetters, mapActions, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';
import { MonitorGroup } from './types';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().subtract(1, 'days').startOf('hour').valueOf(),
      moment().startOf('hour').valueOf(),
    ];
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
      dataTypes: [
        { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
        // { txt: '秒資料', id: 'second' },
      ],
      monitorGroupList: Array<MonitorGroup>(),
      monitorGroup,
      countyFilters,
      county: '',
      form: {
        monitors: Array<any>(),
        monitorTypes: Array<any>(),
        dataType: 'hour',
        range,
      },
      display: false,
      columns: Array<any>(),
      rows: Array<any>(),
      currentPage: 1,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    resultTitle(): string {
      return `總共${this.rows.length}筆`;
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
    await this.fetchMonitorTypes();
    await this.fetchMonitors();
    await this.getMonitorGroups();

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id);
    }

    if (this.monitorTypes.length !== 0) {
      this.form.monitorTypes.push('PM25');
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
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
    async query() {
      let diffHour = (this.form.range[1] - this.form.range[0]) / (1000 * 60 * 60);
      if (this.form.dataType ==='min' && diffHour > 24 * 31) {
        await this.$bvModal.msgBoxOk("查詢區間不可大於31天");
        return;
      }
      this.setLoading({ loading: true });
      this.display = true;
      this.rows = [];
      this.columns = this.getColumns();
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.form.monitorTypes.join(':');
      const url = `/HistoryReport/${monitors}/${monitorTypes}/${this.form.dataType}/${this.form.range[0]}/${this.form.range[1]}`;

      try {
        const ret = await axios.get(url);
        for (const row of ret.data.rows) {
          row.date = moment(row.date).format('lll');
        }
        this.rows = ret.data.rows;
      } catch (err) {
        throw new Error(`${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },
    async getMonitorGroups() {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
    cellDataTd(i: number) {
      return (_value: any, _key: any, item: any) =>
        item.cellData[i].cellClassName;
    },
    getMtDesc(mt: string) {
      const mtCase = this.mtMap.get(mt);
      return `${mtCase.desp}(${mtCase.unit})`;
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'date',
        label: '時間',
        stickyColumn: true,
      });
      let i = 0;
      for (const mt of this.form.monitorTypes) {
        const mtCase = this.mtMap.get(mt);
        for (const m of this.form.monitors) {
          // emtpyCell  ${mtCase.desp}(${mtCase.unit})
          const mCase = this.mMap.get(m);
          ret.push({
            key: `cellData[${i}].v`,
            label: `${mCase.desc}`,
            tdClass: this.cellDataTd(i),
          });
          i++;
        }
      }

      return ret;
    },
    async downloadCSV() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '';
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.form.monitorTypes.join(':');
      const url = `${baseUrl}/HistoryReport/csv/${monitors}/${monitorTypes}/${this.form.dataType}/${this.form.range[0]}/${this.form.range[1]}`;
      window.open(url);
    },
  },
});
</script>

<style></style>
