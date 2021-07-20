<template>
  <div>
    <b-card title="測點管理" class="text-center">
      <div id="sensorFilter" class="mt-2">
        <b-table-simple small fixed>
          <b-tr>
            <b-th>設備碼</b-th>
            <b-th>縣市</b-th>
            <b-th>區域劃分</b-th>
            <b-th>類型</b-th>
            <b-th>群組</b-th>
            <b-th></b-th>
          </b-tr>
          <b-tbody>
            <b-tr>
              <b-td>
                <b-form-input v-model="sensorFilter.code"></b-form-input>
              </b-td>
              <b-td>
                <b-form-select
                  v-model="sensorFilter.county"
                  text-field="txt"
                  :options="countyFilters"
                />
              </b-td>
              <b-td>
                <b-form-select
                  v-model="sensorFilter.district"
                  text-field="txt"
                  :options="districtFilters"
                />
              </b-td>
              <b-td>
                <b-form-select
                  v-model="sensorFilter.sensorType"
                  text-field="txt"
                  :options="sensorTypes"
                />
              </b-td>
              <b-td>
                <v-select
                  v-model="sensorFilter.monitorGroup"
                  label="_id"
                  :reduce="mg => mg"
                  :options="filteredMonitorGroupList"
                />
              </b-td>
              <b-td>
                <b-button
                  v-b-tooltip.hover
                  title="刪除群組"
                  variant="gradient-danger"
                  class="btn-icon rounded-circle mr-2"
                  :disabled="!Boolean(sensorFilter.monitorGroup)"
                  @click="deleteSelectedMonitorGroup"
                >
                  <feather-icon icon="UserMinusIcon" />
                </b-button>
                <b-button
                  v-b-tooltip.hover
                  title="匯出 Excel"
                  class="mr-2"
                  variant="outline-success"
                  size="sm"
                  @click="exportExcel"
                >
                  <b-img src="../assets/excel_export.svg" width="24" fluid />
                </b-button>
                <b-button
                  v-b-tooltip.hover
                  v-b-modal.modal-import-excel
                  title="匯入 Excel"
                  variant="outline-success"
                  size="sm"
                >
                  <b-img src="../assets/excel_import.svg" width="24" fluid />
                </b-button>
              </b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </div>
      <div>總共 {{ filteredMonitors.length }} 個測點</div>
      <b-table
        fixed
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
        <template #table-colgroup="scope">
          <col
            v-for="field in scope.fields"
            :key="field.key"
            :style="{ width: field.wider ? '180px' : '120px' }"
          />
        </template>
        <template #cell(desc)="row">
          <b-form-input v-model="row.item.desc" @change="markDirty(row.item)" />
        </template>
        <template #cell(county)="row">
          <b-form-select
            v-model="row.item.county"
            text-field="txt"
            :options="countySelector"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(district)="row">
          <b-form-select
            v-model="row.item.district"
            text-field="txt"
            :options="getDistrictList(row.item.county)"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(enabled)="row">
          <b-form-checkbox
            v-model="row.item.enabled"
            @change="markDirty(row.item)"
            >{{ getEnabledTxt(row.item.enabled) }}</b-form-checkbox
          >
        </template>
        <template #[`cell(sensorDetail.sensorType)`]="row">
          <b-form-select
            v-model="row.item.sensorDetail.sensorType"
            text-field="txt"
            :options="sensorGroups"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.roadName)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.roadName"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.locationDesc)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.locationDesc"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.authority)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.authority"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.epaCode)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.epaCode"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.target)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.target"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.targetDetail)`]="row">
          <b-form-input
            v-model="row.item.sensorDetail.targetDetail"
            @change="markDirty(row.item)"
          />
        </template>
        <template #[`cell(sensorDetail.height)`]="row">
          <b-form-input
            v-model.number="row.item.sensorDetail.height"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(monitorGroup)="row">
          <b-form-checkbox
            v-model.number="row.item.monitorGroup"
            :disabled="!sensorFilter.monitorGroup"
            @change="markDirty(row.item)"
            >{{ showMonitorGroupID(row.item.monitorGroup) }}</b-form-checkbox
          >
        </template>
        <!-- <template #cell(monitorTypes)="row">
          <v-select
            id="monitorType"
            v-model="row.item.monitorTypes"
            label="desp"
            :reduce="mt => mt._id"
            :options="monitorTypes"
            multiple
            @input="markDirty(row.item)"
          />
        </template> -->
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
    <b-modal id="modal-import-excel" title="匯入感測器Excel" hide-footer>
      <b-form @submit.prevent>
        <b-row>
          <b-col>
            <b-form-file
              v-model="form.uploadFile"
              :state="Boolean(form.uploadFile)"
              accept=".xlsx"
              browse-text="..."
              placeholder="選擇上傳檔案..."
              drop-placeholder="拖曳檔案至此..."
            ></b-form-file>
          </b-col>
        </b-row>
        <br />
        <b-row>
          <b-col>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              v-b-modal.modal-import-excel.hide
              type="submit"
              variant="primary"
              class="mr-1"
              :disabled="!Boolean(form.uploadFile)"
              @click="upload"
            >
              上傳
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-modal>
  </div>
</template>
<style scoped>
.height_field {
  width: 10px;
}
.editable_field {
  width: 120px;
}

.clickable {
  cursor: pointer;
}
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import { mapActions, mapState, mapMutations } from 'vuex';
import axios from 'axios';
import {
  sensorTypes,
  countyFilters,
  getDistrict,
  TxtStrValue,
  Monitor,
  MonitorGroup,
  MonitorExportFields,
} from './types';
import { MonitorState } from '../store/monitors/types';
const excel = require('../libs/excel');
const _ = require('lodash');

interface EditMonitor extends Monitor {
  dirty: undefined | boolean;
  monitorGroup: undefined | boolean;
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
        label: '啟用狀態',
        class: 'text-center',
        sortable: true,
      },
      {
        key: 'monitorGroup',
        label: '群組',
        sortable: true,
      },
      {
        key: '_id',
        label: '設備碼',
        wider: true,
      },
      {
        key: 'code',
        label: '代碼',
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
        key: 'district',
        label: '區域',
        sortable: true,
      },
      {
        key: 'sensorDetail.sensorType',
        label: '感測器類型',
        sortable: true,
        thStyle: {
          editable_field: true,
        },
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
        key: 'sensorDetail.authority',
        label: '所屬單位',
        sortable: true,
      },
      {
        key: 'sensorDetail.epaCode',
        label: 'EPA代碼',
        sortable: true,
      },
      {
        key: 'sensorDetail.target',
        label: '目標',
        sortable: true,
      },
      {
        key: 'sensorDetail.targetDetail',
        label: '目標細分',
        sortable: true,
      },
      {
        key: 'sensorDetail.height',
        label: '高度',
        sortable: true,
      },
    ];
    const sensorGroups = ['SAQ200', 'SAQ210'];
    let monitorGroup: MonitorGroup | undefined | null;
    const form: {
      uploadFile: Blob | undefined;
    } = {
      uploadFile: undefined,
    };
    const countySelector = countyFilters.filter(v => {
      return v.txt !== '不限';
    });
    return {
      sensorFilter: {
        code: '',
        county: '',
        monitorGroup,
        district: '',
        sensorType: '',
      },
      sensorTypes,
      countyFilters,
      countySelector,
      columns,
      sensorGroups,
      currentPage: 1,
      perPage: 15,
      form,
      actorName: '',
      timer: 0,
    };
  },
  computed: {
    ...mapState('monitors', {
      monitors(state: MonitorState) {
        return state.monitors;
      },
      monitorGroupList(state: MonitorState) {
        return state.monitorGroupList;
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
      let editMonitors = this.monitors as Array<EditMonitor>;

      if (this.sensorFilter.monitorGroup instanceof Object) {
        const mg = this.sensorFilter.monitorGroup as MonitorGroup;
        editMonitors.forEach(m => {
          m.monitorGroup = mg.member.indexOf(m._id) !== -1;
        });
      }

      editMonitors.filter(v => {
        if (!v.sensorDetail)
          v.sensorDetail = {
            sensorType: '',
            roadName: '',
            locationDesc: '',
            authority: '',
            epaCode: '',
            target: '',
            targetDetail: '',
            height: 0,
            distance: [1, 1],
          };
      });
      return editMonitors
        .filter(v => {
          return v._id.includes(this.sensorFilter.code);
        })
        .filter((v: Monitor) => {
          if (this.sensorFilter.county === '') return true;
          else return v.county === this.sensorFilter.county;
        })
        .filter(v => {
          /* if (this.sensorFilter.monitorGroup instanceof Object) {
            let mg = this.sensorFilter.monitorGroup as MonitorGroup;
            return mg.member.indexOf(v._id) !== -1;
          } else return true; */
          return true;
        })
        .filter(v => {
          if (this.sensorFilter.district === '') return true;
          else return v.district === this.sensorFilter.district;
        })
        .filter(v => {
          if (this.sensorFilter.sensorType === '') return true;
          else return v.tags.indexOf(this.sensorFilter.sensorType) !== -1;
        });
    },
  },
  watch: {
    'sensorFilter.county': function () {
      if (this.sensorFilter.county === null) this.sensorFilter.county = '';

      // reset district filter
      this.sensorFilter.district = '';
      this.sensorFilter.monitorGroup = null;
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
  beforeDestroy() {
    clearTimeout(this.timer);
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors', 'getMonitorGroups']),
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapMutations(['setLoading']),
    showMonitorGroupID(mg: MonitorGroup) {
      if (mg) return mg._id;
      else return '';
    },
    async save() {
      const all = [];

      for (const m of this.filteredMonitors) {
        if (m.dirty) {
          all.push(axios.put(`/Monitor/${m._id}`, m));
          if (typeof this.sensorFilter.monitorGroup == 'object') {
            const mg = this.sensorFilter.monitorGroup as MonitorGroup;
            if (typeof m.monitorGroup == 'boolean') {
              if (m.monitorGroup) {
                // add it to group
                if (mg.member.indexOf(m._id) === -1) mg.member.push(m._id);
              } else {
                let index = mg.member.indexOf(m._id);
                if (index !== -1) {
                  mg.member.splice(index, 1);
                }
              }
            }
          }
        }
      }

      if (typeof this.sensorFilter.monitorGroup == 'object') {
        const mg = this.sensorFilter.monitorGroup as MonitorGroup;
        all.push(axios.put(`/MonitorGroup/${mg._id}`, mg));
      }

      try {
        await Promise.all(all);
        this.fetchMonitors();
        this.getMonitorGroups();
        this.$bvModal.msgBoxOk('成功');
      } catch (err) {
        throw new Error(err);
      }
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
      return getDistrict(county).filter(v => {
        return v.txt !== '不限';
      });
    },
    exportExcel() {
      const title = MonitorExportFields.map(e => e.name);
      const key = MonitorExportFields.map(e => e.key);
      let exportList = this.filteredMonitors.filter(m => {
        if (m.monitorGroup !== undefined) return m.monitorGroup;
        if (!Boolean(m.code)) return false;

        return true;
      });

      for (let entry of exportList) {
        let e = entry as any;
        for (let field of MonitorExportFields) {
          let arg = _.get(entry, field.key);
          if (field.getter === undefined) e[field.key] = arg;
          else e[field.key] = field.getter(arg);
        }
      }

      let filename = this.sensorFilter.monitorGroup
        ? `${this.sensorFilter.monitorGroup._id}感測器`
        : '感測器';

      const params = {
        title,
        key,
        data: exportList,
        autoWidth: true,
        filename,
      };
      excel.export_array_to_excel(params);
    },
    upload() {
      this.$bvModal.hide('modal-import-excel');
      var formData = new FormData();
      formData.append('data', this.form.uploadFile as Blob);
      this.setLoading({ loading: true, message: '資料上傳中' });
      axios
        .post(`/ImportData/monitor`, formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
        })
        .then(res => {
          if (res.status === 200) {
            this.actorName = res.data.actorName;
            this.setLoading({ loading: true, message: '資料庫匯入中' });
            this.timer = setTimeout(this.checkFinished, 1000);
            //this.$bvModal.msgBoxOk('上傳成功', { headerBgVariant: 'info' });
          } else {
            this.setLoading({ loading: false });
            this.$bvModal.msgBoxOk(
              `上傳失敗 ${res.status} - ${res.statusText}`,
              {
                headerBgVariant: 'danger',
              },
            );
          }
        })
        .catch(err => {
          this.setLoading({ loading: false });
          alert(err);
        });
    },
    async checkFinished() {
      const res = await axios.get(`/UploadProgress/${this.actorName}`);
      if (res.data.finished) {
        this.setLoading({ loading: false });
        this.$bvModal.msgBoxOk('上傳成功', { headerBgVariant: 'info' });
      } else {
        this.timer = setTimeout(this.checkFinished, 1000);
      }
    },
    async deleteSelectedMonitorGroup() {
      if (typeof this.sensorFilter.monitorGroup == 'object') {
        const mg = this.sensorFilter.monitorGroup as MonitorGroup;
        try {
          const ret = await axios.delete(`/MonitorGroup/${mg._id}`);
          if (ret.data.ok) {
            this.$bvModal.msgBoxOk('刪除成功', { headerBgVariant: 'info' });
            this.getMonitorGroups();
            this.sensorFilter.monitorGroup = undefined;
          } else
            this.$bvModal.msgBoxOk('刪除失敗', { headerBgVariant: 'danger' });
        } catch (err) {
          throw new Error(err);
        }
      }
    },
  },
});
</script>
