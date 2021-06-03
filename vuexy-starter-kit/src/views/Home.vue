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
                    `即時資訊/${group.name}`
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
              :icon="m.iconUrl"
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
<script>
import { mapActions, mapState, mapGetters } from 'vuex';
import axios from 'axios';

export default {
  data() {
    return {
      mapLayer: ['sensor', 'EPA'],
      sensorStatus: [],
      epaStatus: [],
      disconnectedList: [],
      constantList: [],
      lt95List: [],
      errorStatus: [],
      sensorStatusParam: {
        pm25Threshold: '',
        county: '基隆市',
        district: '',
        sensorType: '',
      },
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,

      infoOptions: {
        content: '',
        // optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
      sensorGroupSummary: [],
      pm25Filters: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: 'PM2.5 < 1',
          value: -1,
        },
        {
          txt: 'PM2.5 > 25',
          value: 25,
        },
        {
          txt: 'PM2.5 > 50',
          value: 50,
        },
      ],
      countyFilters: [
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
      ],
      sensorTypes: [
        {
          txt: '不限',
          value: '',
        },
        {
          txt: '工業區',
          value: 'ID',
        },
        {
          txt: '其他汙染',
          value: 'OT',
        },
        {
          txt: '社區',
          value: 'CO',
        },
        {
          txt: '交通',
          value: 'TR',
        },
        {
          txt: '監測比對',
          value: 'MO',
        },
        {
          txt: '長期比對',
          value: 'LO',
        },
        {
          txt: '巡檢機',
          value: 'AO',
        },
      ],
      mapLayerTypes: [
        {
          txt: '感測器',
          value: 'sensor',
        },
        {
          txt: '環保署',
          value: 'EPA',
        },
      ],
      errorFilters: [
        {
          txt: '通訊中斷',
          value: 'disconnect',
        },
        {
          txt: '完整率 < 90%',
          value: 'lt95',
        },
        {
          txt: '定值',
          value: 'constant',
        },
      ],
      epaIconImage: undefined,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    districtFilters() {
      if (this.sensorStatusParam.county === '基隆市') {
        return [
          { txt: '不限', value: '' },
          { txt: '安樂區', value: 'AL' },
          { txt: '七堵區', value: 'QD' },
          { txt: '仁愛區', value: 'RA' },
          { txt: '中正區', value: 'ZZ' },
          { txt: '暖暖區', value: 'NN' },
          { txt: '中山區', value: 'ZS' },
          { txt: '信義區', value: 'XY' },
        ];
      }
      if (this.sensorStatusParam.county === '屏東縣') {
        return [
          { txt: '不限', value: '' },
          { txt: '屏東市', value: 'PT' },
          { txt: '恆春鎮', value: 'HC' },
          { txt: '琉球鄉', value: 'LQ' },
          { txt: '內埔鄉', value: 'NP' },
          { txt: '麟洛鄉', value: 'LL' },
          { txt: '車城鄉', value: 'CC' },
          { txt: '九如鄉', value: 'JR' },
          { txt: '三地門鄉', value: 'SD' },
          { txt: '里港鄉', value: 'LG' },
          { txt: '霧台鄉', value: 'WT' },
          { txt: '鹽埔鄉', value: 'YP' },
          { txt: '佳冬鄉', value: 'JD' },
          { txt: '竹田鄉', value: 'JT' },
          { txt: '長治鄉', value: 'CJ' },
          { txt: '東港鎮', value: 'DG' },
          { txt: '枋山鄉', value: 'FS' },
          { txt: '新園鄉', value: 'SY' },
          { txt: '枋寮鄉', value: 'FL' },
          { txt: '瑪家鄉', value: 'MJ' },
          { txt: '泰武鄉', value: 'TW' },
          { txt: '潮州鎮', value: 'CZ' },
          { txt: '來義鄉', value: 'LY' },
          { txt: '新埤鄉', value: 'SP' },
          { txt: '南州鄉', value: 'NC' },
          { txt: '萬巒鄉', value: 'WL' },
          { txt: '林邊鄉', value: 'LB' },
          { txt: '崁頂鄉', value: 'KD' },
          { txt: '獅子鄉', value: 'SZ' },
          { txt: '萬丹鄉', value: 'WD' },
          { txt: '高樹鄉', value: 'GS' },
          { txt: '滿州鄉', value: 'MZ' },
          { txt: '牡丹鄉', value: 'MD' },
          { txt: '春日鄉', value: 'CR' },
        ];
      }
      if (this.sensorStatusParam.county === '宜蘭縣') {
        return [
          { txt: '不限', value: '' },
          { txt: '蘇澳鎮', value: 'SA' },
          { txt: '冬山鄉', value: 'DS' },
          { txt: '南澳鄉', value: 'NA' },
          { txt: '五結鄉', value: 'WJ' },
          { txt: '壯圍鄉', value: 'ZW' },
          { txt: '宜蘭市', value: 'YL' },
          { txt: '羅東鎮', value: 'LD' },
          { txt: '頭城鎮', value: 'TC' },
          { txt: '礁溪鄉', value: 'JS' },
          { txt: '員山鄉', value: 'YS' },
          { txt: '三星鄉', value: 'SS' },
          { txt: '大同鄉', value: 'DT' },
        ];
      }
      return [{ txt: '不限', value: '' }];
    },
    mapCenter() {
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
    sensorMarkers() {
      if (
        this.mapLayer.indexOf('sensor') === -1 ||
        this.errorStatus.length !== 0
      )
        return [];

      return this.markers(this.sensorStatus);
    },
    epaMarkers() {
      if (this.mapLayer.indexOf('EPA') === -1) return [];
      return this.markers(this.epaStatus);
    },
    constantMarkers() {
      if (this.errorStatus.indexOf('constant') === -1) return [];

      return this.markers(this.constantList);
    },
    lt95Markers() {
      if (this.errorStatus.indexOf('lt95') === -1) return [];

      return this.markers(this.lt95List);
    },
    disconnectedMarkers() {
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
    this.$refs.mapRef.$mapPromise.then(map => {
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
    toggleInfoWindow(marker, idx) {
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
    getPM25Class(v) {
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
    async getEpaStatus() {
      const ret = await axios.get('/RealtimeEPA');
      this.epaStatus = ret.data;
    },
    async getDisconnected() {
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
    async getConstantValue() {
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
    async getLt95List() {
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
    async getTodaySummary() {
      const res = await axios.get('/SensorSummary');
      const ret = res.data;
      this.sensorGroupSummary = ret;
    },
    handleErrorStatusChange(newMap, oldMap) {
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
    handlMapLayerChange(newMap, oldMap) {
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
    markers(statusArray) {
      const ret = [];
      const epaUrl = (name, v) => {
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

      const getIconUrl = v => {
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

        const pm25Entry = stat.mtDataList.find(v => v.mtName === 'PM25');

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
};
</script>
