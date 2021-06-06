<template>
  <div>
    <b-card title="測點管理" class="text-center">
      <div id="sensorFilter" class="mt-2">
        <b-table-simple small>
          <b-tr>
            <b-th>縣市</b-th>
            <b-th>群組</b-th>
            <b-th>區域劃分</b-th>
            <b-th>類型</b-th>
          </b-tr>
          <b-tbody>
            <b-tr>
              <b-td
                ><v-select
                  v-model="sensorFilter.county"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="countyFilters"
              /></b-td>
              <b-td>
                <v-select
                  v-model="sensorFilter.monitorGroup"
                  label="_id"
                  :reduce="mg => mg"
                  :options="filteredMonitorGroupList"
                />
              </b-td>
              <b-td
                ><v-select
                  v-model="sensorFilter.district"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="districtFilters"
              /></b-td>
              <b-td
                ><v-select
                  v-model="sensorFilter.sensorType"
                  label="txt"
                  :reduce="entry => entry.value"
                  :options="sensorTypes"
              /></b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </div>
      <div>總共 {{ filteredMonitors.length }} 個測點</div>
      <b-table
        small
        responsive
        :fields="columns"
        :items="filteredMonitors"
        bordered
        sticky-header
        style="min-height: 600px"
        :per-page="perPage"
        :current-page="currentPage"
      >
        <template #cell(desc)="row">
          <b-form-input v-model="row.item.desc" @change="markDirty(row.item)" />
        </template>
        <template #cell(county)="row">
          <v-select
            v-model="row.item.county"
            label="txt"
            :reduce="entry => entry.value"
            :options="countyFilters"
            @input="markDirty(row.item)"
          />
        </template>
        <template #cell(district)="row">
          <v-select
            v-model="row.item.district"
            label="txt"
            :reduce="entry => entry.value"
            :options="getDistrictList(row.item.county)"
            @input="markDirty(row.item)"
          />
        </template>
        <template #cell(enabled)="row">
          <b-form-checkbox
            v-model="row.item.enabled"
            @change="markDirty(row.item)"
            >{{ getEnabledTxt(row.item.enabled) }}</b-form-checkbox
          >
        </template>
        <template #cell(monitorTypes)="row">
          <v-select
            id="monitorType"
            v-model="row.item.monitorTypes"
            label="desp"
            :reduce="mt => mt._id"
            :options="monitorTypes"
            multiple
            @input="markDirty(row.item)"
          />
        </template>
      </b-table>
      <b-pagination
        v-model="currentPage"
        :total-rows="filteredMonitors.length"
        :per-page="perPage"
        first-text="⏮"
        prev-text="⏪"
        next-text="⏩"
        last-text="⏭"
        class="mt-4"
      ></b-pagination>
      <b-row>
        <b-col>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="primary"
            class="mr-1"
            @click="save"
          >
            儲存
          </b-button>
          <b-button
            v-ripple.400="'rgba(186, 191, 199, 0.15)'"
            type="reset"
            variant="outline-secondary"
            @click="rollback"
          >
            取消
          </b-button>
        </b-col>
      </b-row>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import { mapActions, mapState } from 'vuex';
import axios from 'axios';
import {
  sensorTypes,
  countyFilters,
  getDistrict,
  TxtStrValue,
  MonitorGroup,
} from './types';
import { MonitorState, Monitor } from '../store/monitors/types';

interface EditMonitor extends Monitor {
  dirty: undefined | boolean;
}

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const columns = [
      {
        key: 'enabled',
        label: '',
        class: 'text-center',
        sortable: true,
      },
      {
        key: '_id',
        label: '代碼',
      },
      {
        key: 'code',
        label: '設備碼',
        sortable: true,
      },
      {
        key: 'county',
        label: '縣市',
        sortable: true,
      },
      {
        key: 'district',
        label: '區域',
        sortable: true,
      },
      {
        key: 'monitorTypes',
        label: '測項',
        sortable: true,
      },
    ];
    let monitorGroup: MonitorGroup | undefined | null;
    return {
      sensorFilter: {
        county: '',
        monitorGroup,
        district: '',
        sensorType: '',
      },
      monitorGroupList: Array<MonitorGroup>(),
      sensorTypes,
      countyFilters,
      columns,
      currentPage: 1,
      perPage: 15,
    };
  },
  computed: {
    ...mapState('monitors', {
      monitors(state: MonitorState) {
        return state.monitors;
      },
    }),
    ...mapState('monitorTypes', ['monitorTypes']),
    districtFilters(): Array<TxtStrValue> {
      return getDistrict(this.sensorFilter.county);
    },
    filteredMonitorGroupList(): Array<MonitorGroup> {
      if (this.sensorFilter.county === '') return this.monitorGroupList;
      else {
        return this.monitorGroupList.filter(
          (value: MonitorGroup, index: number) => {
            let prefix = '';
            switch (this.sensorFilter.county) {
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
    filteredMonitors(): Array<EditMonitor> {
      let editMonitor = this.monitors as Array<EditMonitor>;
      return editMonitor
        .filter((v: Monitor) => {
          if (this.sensorFilter.county === '') return true;
          else return v.county === this.sensorFilter.county;
        })
        .filter(v => {
          if (this.sensorFilter.monitorGroup instanceof Object) {
            let mg = this.sensorFilter.monitorGroup as MonitorGroup;
            return mg.member.indexOf(v._id) !== -1;
          } else return true;
        })
        .filter(v => {
          if (this.sensorFilter.district === '') return true;
          else return v.district === this.sensorFilter.district;
        })
        .filter(v => {
          if (this.sensorFilter.sensorType === '') return true;
          else
            return v.sensorDetail?.sensorType === this.sensorFilter.sensorType;
        });
    },
  },
  watch: {
    'sensorFilter.county': function () {
      if (this.sensorFilter.county === null) this.sensorFilter.county = '';

      // reset district filter
      this.sensorFilter.district = '';
    },
    'sensorFilter.district': function () {
      if (this.sensorFilter.district === null) this.sensorFilter.district = '';
    },
    'sensorFilter.sensorType': function () {
      if (this.sensorFilter.sensorType === null)
        this.sensorFilter.sensorType = '';
    },
  },
  async mounted() {
    await this.fetchMonitors();
    await this.fetchMonitorTypes();
    await this.getMonitorGroups();
  },

  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    async getMonitorGroups() {
      const ret = await axios.get('/MonitorGroups');
      this.monitorGroupList = ret.data;
    },
    save() {
      const all = [];
      for (const m of this.filteredMonitors) {
        if (m.dirty) {
          all.push(axios.put(`/Monitor/${m._id}`, m));
        }
      }

      Promise.all(all).then(() => {
        this.fetchMonitors();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    rollback() {
      this.fetchMonitors();
    },
    markDirty(item: EditMonitor) {
      item.dirty = true;
    },
    getEnabledTxt(v: boolean) {
      return v ? '啟用' : '未啟用';
    },
    getDistrictList(county: string) {
      return getDistrict(county);
    },
  },
});
</script>

<style></style>
