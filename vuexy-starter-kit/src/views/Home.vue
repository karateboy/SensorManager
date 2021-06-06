<template>
  <b-row class="match-height">
    <b-col lg="12" md="12">
      <b-card ref="loadingContainer">
        <b-row>
          <b-col><div id="chart_container1" /></b-col>
          <b-col><div id="chart_container2" /></b-col>
          <b-col><div id="chart_container3" /></b-col>
        </b-row>
        <b-row>
          <b-col v-for="group in sensorGroupSummary" :key="group.name">
            <b-table-simple bordered responsive outlined>
              <b-thead>
                <b-tr
                  ><b-td class="text-center" colspan="5">{{
                    `${group.name}即時資訊`
                  }}</b-td></b-tr
                >
                <b-tr>
                  <b-th></b-th>
                  <b-th>基隆</b-th>
                  <b-th>宜蘭</b-th>
                  <b-th>屏東</b-th>
                  <b-th>其他</b-th>
                </b-tr>
              </b-thead>
              <b-tbody>
                <b-tr>
                  <b-td>設置數量</b-td>
                  <b-td>{{ group.totalCount.kl }} </b-td>
                  <b-td>{{ group.totalCount.yl }} </b-td>
                  <b-td>{{ group.totalCount.pt }}</b-td>
                  <b-td>{{ group.totalCount.rest }}</b-td>
                </b-tr>
                <b-tr>
                  <b-td>接收數量</b-td>
                  <b-td>{{ group.count.kl }} </b-td>
                  <b-td>{{ group.count.yl }} </b-td>
                  <b-td>{{ group.count.pt }}</b-td>
                  <b-td>{{ group.count.rest }}</b-td>
                </b-tr>
                <b-tr>
                  <b-td>完整率&lt;90%</b-td>
                  <b-td>{{ group.lessThanExpected.kl }} </b-td>
                  <b-td>{{ group.lessThanExpected.yl }} </b-td>
                  <b-td>{{ group.lessThanExpected.pt }}</b-td>
                  <b-td>{{ group.lessThanExpected.rest }}</b-td>
                </b-tr>
                <b-tr>
                  <b-td>定值(10分鐘)</b-td>
                  <b-td>{{ group.constant.kl }} </b-td>
                  <b-td>{{ group.constant.yl }} </b-td>
                  <b-td>{{ group.constant.pt }}</b-td>
                  <b-td>{{ group.constant.rest }}</b-td>
                </b-tr>
                <b-tr>
                  <b-td>通訊中斷(前10分鐘)</b-td>
                  <b-td>{{ group.disconnected.kl }} </b-td>
                  <b-td>{{ group.disconnected.yl }} </b-td>
                  <b-td>{{ group.disconnected.pt }}</b-td>
                  <b-td>{{ group.disconnected.rest }}</b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
    <b-col lg="12" md="12">
      <b-card>
        <b-row>
          <b-col cols="2">
            <b-table-simple borderless>
              <b-tbody>
                <b-tr>
                  <b-td class="text-center align-middle"
                    ><h3>監測地圖</h3></b-td
                  >
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col cols="10"
            ><b-img src="../assets/images/legend.png" fluid class="float-right"
          /></b-col>
        </b-row>

        <!-- <div id="legend" class="legend shadow border border-dark m-2">
          <b-img src="../assets/images/legend.png" fluid />
        </div> -->
        <div class="map_container">
          <div id="mapFilter" class="sensorFilter mt-2 ml-2">
            <b-table-simple small>
              <b-tr>
                <b-th>圖層選擇</b-th>
              </b-tr>
              <b-tbody>
                <b-tr>
                  <b-td>
                    <v-select
                      v-model="mapLayer"
                      label="txt"
                      multiple
                      :reduce="entry => entry.value"
                      :options="mapLayerTypes"
                    />
                  </b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </div>
          <div id="sensorFilter" class="sensorFilter mt-2">
            <b-table-simple small>
              <b-tr>
                <b-th>縣市</b-th>
                <b-th>區域劃分</b-th>
                <b-th>類型</b-th>
                <b-th>濃度</b-th>
                <b-th>異常狀態</b-th>
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
                      v-model="sensorStatusParam.pm25Threshold"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="pm25Filters"
                  /></b-td>
                  <b-td
                    ><v-select
                      v-model="errorStatus"
                      label="txt"
                      :reduce="entry => entry.value"
                      :options="errorFilters"
                      multiple
                  /></b-td>
                </b-tr>
              </b-tbody>
            </b-table-simple>
          </div>
          <GmapMap
            ref="mapRef"
            :center="mapCenter"
            :zoom="12"
            map-type-id="roadmap"
            class="map_canvas"
            :options="{
              zoomControl: true,
              mapTypeControl: false,
              scaleControl: false,
              streetViewControl: false,
              rotateControl: false,
              fullscreenControl: true,
              disableDefaultUi: false,
            }"
          >
            <GmapMarker
              v-for="(m, index) in sensorMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <GmapMarker
              v-for="(m, index) in epaMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              label="EPA"
              :share="shape"
              @click="toggleInfoWindow(m, index)"
            />
            <GmapMarker
              v-for="(m, index) in constantMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <GmapMarker
              v-for="(m, index) in disconnectedMarkers"
              :key="m._id"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <GmapMarker
              v-for="(m, index) in lt95Markers"
              :key="m._id + 'lt95'"
              :position="m.position"
              :clickable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <gmap-info-window
              :options="infoOptions"
              :position="infoWindowPos"
              :opened="infoWinOpen"
              @closeclick="infoWinOpen = false"
            />
          </GmapMap>
        </div>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
.sensorFilter {
  background-color: white;
}

.legend {
  /* min-width: 100px;*/
  background-color: white;
}

.airgreen div:before {
  background: #009865;
  background-color: rgb(0, 152, 101);
}

.airgreen {
  background-color: rgb(229, 244, 239);
}
</style>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapState, mapGetters } from 'vuex';
import axios from 'axios';
import {
  sensorTypes,
  pm25Filters,
  countyFilters,
  errorFilters,
  getDistrict,
  TxtStrValue,
  MtRecord,
} from './types';

interface Location {
  lat: number;
  lng: number;
}

export default Vue.extend({
  data() {
    const mapLayerTypes = [
      {
        txt: '感測器',
        value: 'sensor',
      },
      {
        txt: '環保署',
        value: 'EPA',
      },
    ];
    const sensorStatusParam = {
      pm25Threshold: '',
      county: '基隆市',
      district: '',
      sensorType: '',
    };
    const infoOptions = {
      content: '',
      // optional: offset infowindow so it visually sits nicely on top of our marker
      pixelOffset: {
        width: 0,
        height: -35,
      },
    };

    let sensorStatus = Array<any>();
    let epaStatus = Array<any>();
    let disconnectedList = Array<any>();
    let constantList = Array<any>();
    let lt95List = Array<any>();
    let errorStatus = Array<string>();
    let sensorGroupSummary = Array<any>();
    let currentMidx: number = -1;
    return {
      sensorStatusParam,
      mapLayer: ['sensor', 'EPA'],
      sensorStatus,
      epaStatus,
      disconnectedList,
      constantList,
      lt95List,
      errorStatus,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx,
      infoOptions,
      sensorGroupSummary,
      pm25Filters,
      countyFilters,
      sensorTypes,
      mapLayerTypes,
      errorFilters,
      shape: {
        coords: [10, 10, 10, 15, 15, 15, 15, 10],
        type: 'poly',
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    districtFilters(): Array<TxtStrValue> {
      return getDistrict(this.sensorStatusParam.county);
    },
    mapCenter(): Location {
      const { county } = this.sensorStatusParam;
      switch (county) {
        case '基隆市':
          return { lat: 25.127594828422044, lng: 121.7399713796935 };
        case '宜蘭縣':
          return { lat: 24.699449555878495, lng: 121.73719578861895 };
        case '屏東縣':
          return { lat: 22.55311029065028, lng: 120.55724117206266 };
      }

      return { lat: 25.127594828422044, lng: 121.7399713796935 };
    },
    sensorMarkers(): any {
      if (
        this.mapLayer.indexOf('sensor') === -1 ||
        this.errorStatus.length !== 0
      )
        return [];

      return this.markers(this.sensorStatus);
    },
    epaMarkers(): Array<any> {
      if (this.mapLayer.indexOf('EPA') === -1) return [];
      return this.markers(this.epaStatus);
    },
    constantMarkers(): Array<any> {
      if (this.errorStatus.indexOf('constant') === -1) return [];

      return this.markers(this.constantList);
    },
    lt95Markers(): Array<any> {
      if (this.errorStatus.indexOf('lt95') === -1) return [];

      return this.markers(this.lt95List);
    },
    disconnectedMarkers(): Array<any> {
      if (this.errorStatus.indexOf('disconnect') === -1) return [];

      const ret = [];

      for (const id of this.disconnectedList) {
        const m = this.mMap.get(id);
        if (!m || !m.location) continue;

        const lng = m.location[0];
        const lat = m.location[1];

        const iconUrl =
          'https://chart.googleapis.com/chart?chst=d_map_pin_icon&chld=caution|FF0000';

        const infoText = m.code
          ? `<strong>${m.shortCode}/${m.code}</strong>`
          : `<strong>${m.desc}</strong>`;
        const title = m.code ? `斷線 ${m.code}` : `${m.desc}`;

        ret.push({
          _id: id,
          title,
          position: { lat, lng },
          infoText,
          iconUrl,
        });
      }
      return ret;
    },
  },
  watch: {
    'sensorStatusParam.pm25Threshold': function () {
      if (this.sensorStatusParam.pm25Threshold === null)
        this.sensorStatusParam.pm25Threshold = '';

      this.refreshMapStatus();
    },
    'sensorStatusParam.county': function () {
      if (this.sensorStatusParam.county === null)
        this.sensorStatusParam.county = '';

      // reset district filter
      this.sensorStatusParam.district = '';
      this.refreshMapStatus();
    },
    'sensorStatusParam.district': function () {
      if (this.sensorStatusParam.district === null)
        this.sensorStatusParam.district = '';

      this.refreshMapStatus();
    },
    'sensorStatusParam.sensorType': function () {
      if (this.sensorStatusParam.sensorType === null)
        this.sensorStatusParam.sensorType = '';

      this.refreshMapStatus();
    },
    mapLayer(newMap, oldMap) {
      this.handlMapLayerChange(newMap, oldMap);
    },
    errorStatus(newError, oldError) {
      this.handleErrorStatusChange(newError, oldError);
    },
  },
  async mounted() {
    const sensorFilter = document.getElementById('sensorFilter');
    const mapFilter = document.getElementById('mapFilter');
    let ref = this.$refs.mapRef as any;
    ref.$mapPromise.then((map: google.maps.Map) => {
      map.controls[google.maps.ControlPosition.TOP_CENTER].push(sensorFilter);
      map.controls[google.maps.ControlPosition.TOP_LEFT].push(mapFilter);
    });

    await this.fetchMonitors();
    await this.fetchMonitorTypes();
    this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 60000);
    this.handlMapLayerChange(this.mapLayer, []);
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    refreshMapStatus() {
      if (this.mapLayer.indexOf('sensor') !== -1) this.getSensorStatus();
      if (this.errorStatus.indexOf('disconnect') !== -1) this.getDisconnected();
      if (this.errorStatus.indexOf('constant') !== -1) this.getConstantValue();
      if (this.errorStatus.indexOf('lt95') !== -1) this.getLt95List();
    },
    toggleInfoWindow(marker: any, idx: number) {
      this.infoWindowPos = marker.position;
      this.infoOptions.content = marker.infoText;

      // check if its the same marker that was selected if yes toggle
      if (this.currentMidx == idx) {
        this.infoWinOpen = !this.infoWinOpen;
      }

      // if different marker set infowindow to open and reset current marker index
      else {
        this.infoWinOpen = true;
        this.currentMidx = idx;
      }
    },
    getPM25Class(v: number) {
      if (v < 12) return { FPMI1: true };
      if (v < 24) return { FPMI2: true };
      if (v < 36) return { FPMI3: true };
      if (v < 42) return { FPMI4: true };
      if (v < 48) return { FPMI5: true };
      if (v < 54) return { FPMI6: true };
      if (v < 59) return { FPMI7: true };
      if (v < 65) return { FPMI8: true };
      if (v < 71) return { FPMI9: true };
      return { FPMI10: true };
    },
    async refresh() {
      this.getTodaySummary();
    },
    async getSensorStatus() {
      const ret = await axios.get('/RealtimeSensor', {
        params: this.sensorStatusParam,
      });
      this.sensorStatus = ret.data;
    },
    async getEpaStatus(): Promise<void> {
      const ret = await axios.get('/RealtimeEPA');
      this.epaStatus = ret.data;
    },
    async getDisconnected(): Promise<void> {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/RealtimeDisconnectedSensor', {
        params,
      });
      this.disconnectedList = ret.data;
    },
    async getConstantValue(): Promise<void> {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/RealtimeConstantValueSensor', {
        params,
      });
      this.constantList = ret.data;
    },
    async getLt95List(): Promise<void> {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/Lt95Sensor', {
        params,
      });

      this.lt95List = ret.data;
    },
    async getTodaySummary(): Promise<void> {
      const res = await axios.get('/SensorSummary');
      const ret = res.data;
      this.sensorGroupSummary = ret;
    },
    handleErrorStatusChange(
      newMap: Array<string>,
      oldMap: Array<string>,
    ): void {
      const mapToClear = oldMap.filter(map => newMap.indexOf(map) === -1);
      mapToClear.forEach(map => {
        switch (map) {
          case 'disconnect':
            this.disconnectedList = [];
            break;
          case 'constant':
            this.constantList = [];
            break;
          case 'lt95':
            this.lt95List = [];
            break;
        }
      });
      const mapToGet = newMap.filter(map => oldMap.indexOf(map) === -1);
      mapToGet.forEach(map => {
        switch (map) {
          case 'disconnect':
            this.getDisconnected();
            break;
          case 'constant':
            this.getConstantValue();
            break;
          case 'lt95':
            this.getLt95List();
        }
      });
    },
    handlMapLayerChange(newMap: Array<string>, oldMap: Array<string>): void {
      const mapToClear = oldMap.filter(map => newMap.indexOf(map) === -1);
      mapToClear.forEach(map => {
        switch (map) {
          case 'sensor':
            this.sensorStatus = [];
            break;
          case 'EPA':
            this.epaStatus = [];
            break;
        }
      });
      const mapToGet = newMap.filter(map => oldMap.indexOf(map) === -1);
      mapToGet.forEach(map => {
        switch (map) {
          case 'sensor':
            this.getSensorStatus();
            break;
          case 'EPA':
            this.getEpaStatus();
            break;
        }
      });
    },
    markers(statusArray: Array<any>): Array<any> {
      const ret = [];
      const epaUrl = (name: string, v: number): string => {
        let url = `https://chart.googleapis.com/chart?chst=d_map_spin&chld=1.5|0|`;

        if (v < 15.4) url += `009865`;
        else if (v < 35.4) url += `FFFB26`;
        else if (v < 54.4) url += `FF9835`;
        else if (v < 150.4) url += `CA0034`;
        else if (v < 250.4) url += `670099`;
        else if (v < 350.4) url += `7E0123`;
        else url += `7E0123`;

        url += `|17|b|${v}`;

        return url;
      };

      const getIconUrl = (v: number) => {
        let url =
          'https://chart.googleapis.com/chart?chst=d_bubble_text_small_withshadow&&chld=bb|';

        let valueStr = v.toFixed(this.mtMap.get('PM25').prec);
        if (v < 15.4) url += `${valueStr}|009865|000000`;
        else if (v < 35.4) url += `${valueStr}|FFFB26|000000`;
        else if (v < 54.4) url += `${valueStr}|FF9835|000000`;
        else if (v < 150.4) url += `${valueStr}|CA0034|000000`;
        else if (v < 250.4) url += `${valueStr}|670099|000000`;
        else if (v < 350.4) url += `${valueStr}|7E0123|000000`;
        else url += `${valueStr}|7E0123|FFFFFF`;

        return url;
      };

      for (const stat of statusArray) {
        if (!stat.location) continue;

        const { _id } = stat;
        const lng = stat.location[0];
        const lat = stat.location[1];

        let pm25 = 0;

        const pm25Entry = stat.mtDataList.find(
          (v: MtRecord) => v.mtName === 'PM25',
        );

        if (!pm25Entry) continue;
        pm25 = pm25Entry.value;

        const iconUrl = stat.tags.includes('EPA')
          ? epaUrl(this.mMap.get(stat._id).desc, pm25)
          : getIconUrl(pm25);

        const infoText = stat.code
          ? `<strong>${stat.shortCode}/${stat.code}</strong>`
          : `<strong>${this.mMap.get(stat._id).desc}</strong>`;
        const title = stat.code
          ? `${stat.code}`
          : `${this.mMap.get(stat._id).desc}`;

        ret.push({
          _id,
          title,
          position: { lat, lng },
          pm25,
          infoText,
          iconUrl,
        });
      }

      return ret;
    },
  },
});
</script>
