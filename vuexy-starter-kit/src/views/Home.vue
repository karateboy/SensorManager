<template>
  <b-row class="match-height">
    <b-col lg="12" md="12">
      <b-card ref="loadingContainer" title="過去24小時統計">
        <b-row>
          <b-col><div id="chart_container1" /></b-col>
          <b-col><div id="chart_container2" /></b-col>
          <b-col><div id="chart_container3" /></b-col>
        </b-row>
        <b-table-simple>
          <b-thead>
            <b-tr>
              <b-th>群組</b-th>
              <b-th>感測器數</b-th>
              <b-th>擷取率95%以上</b-th>
              <b-th>低於95%</b-th>
              <b-th>斷線</b-th>
            </b-tr>
          </b-thead>
          <b-tbody>
            <b-tr v-for="group in sensorGroupSummary" :key="group.name">
              <b-td>{{ group.name }}</b-td>
              <b-td>{{ group.count }} </b-td>
              <b-td>{{ group.expected }}</b-td>
              <b-td>{{ group.below }}</b-td>
              <b-td>{{ group.count - group.expected - group.below }}</b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </b-card>
    </b-col>
    <b-col lg="12" md="12">
      <b-card title="監測地圖🚀">
        <b-row>
          <b-col cols="3">PM25 過濾:</b-col>
          <b-col cols="3">
            <v-select
              id="pm25Filter"
              v-model="realTimeStatusFilter.pm25Threshold"
              label="txt"
              :reduce="entry => entry.value"
              :options="pm25Filters"
            />
          </b-col>
        </b-row>
        <br />
        <div class="map_container">
          <GmapMap
            ref="mapRef"
            :center="mapCenter"
            :zoom="8"
            map-type-id="terrain"
            class="map_canvas"
          >
            <GmapMarker
              v-for="(m, index) in markers"
              :key="index"
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

          <div id="legend" class="legend shadow border border-dark m-2">
            <b-img src="../assets/images/legend.png" width="130" />
          </div>
        </div>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
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
import highcharts from 'highcharts';
export default {
  data() {
    return {
      realTimeStatusRaw: [],
      realTimeStatusFilter: {
        pm25Threshold: 25,
      },
      loader: undefined,
      timer: 0,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,

      infoOptions: {
        content: '',
        //optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
      sensorGroupSummary: [],
      pm25Filters: [
        {
          txt: '全部',
          value: 0,
        },
        {
          txt: 'pm25 > 35.4',
          value: 35.4,
        },
        {
          txt: 'pm25 > 54.4',
          value: 54.4,
        },
        {
          txt: 'pm25 > 150.4',
          value: 150.4,
        },
        {
          txt: 'pm25 > 250.4',
          value: 250.4,
        },
      ],
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    realTimeStatus() {
      return this.realTimeStatusRaw.filter(stat => {
        const pm25Entry = stat.mtDataList.find(v => v.mtName === 'PM25');

        if (!pm25Entry) return true;
        const pm25 = pm25Entry.value;
        return pm25 > this.realTimeStatusFilter.pm25Threshold;
      });
    },
    mapCenter() {
      if (this.realTimeStatus.length === 0)
        return { lat: 23.97397424582721, lng: 120.97969180002414 };

      let latMax = -1,
        latMin = 1000,
        lngMax = -1,
        lngMin = 1000;

      for (const stat of this.realTimeStatus) {
        const latEntry = stat.mtDataList.find(v => v.mtName === 'LAT');
        if (!latEntry) continue;

        if (latMax < latEntry.value) latMax = latEntry.value;
        if (latMin > latEntry.value) latMin = latEntry.value;

        const lngEntry = stat.mtDataList.find(v => v.mtName === 'LNG');
        if (!lngEntry) continue;
        if (lngMax < lngEntry.value) lngMax = lngEntry.value;
        if (lngMin > lngEntry.value) lngMin = lngEntry.value;
      }

      return { lat: (latMax + latMin) / 2, lng: (lngMax + lngMin) / 2 };
    },
    markers() {
      const ret = [];
      let count = 0;
      const getIconUrl = v => {
        let url = `https://chart.googleapis.com/chart?chst=d_bubble_text_small_withshadow&&chld=bb|`;

        if (v < 15.4) url += `${v}|009865|000000`;
        else if (v < 35.4) url += `${v}|FFFB26|000000`;
        else if (v < 54.4) url += `${v}|FF9835|000000`;
        else if (v < 150.4) url += `${v}|CA0034|000000`;
        else if (v < 250.4) url += `${v}|670099|000000`;
        else if (v < 350.4) url += `${v}|7E0123|000000`;
        else url += `${v}|7E0123|FFFFFF`;

        return url;
      };

      for (const stat of this.realTimeStatus) {
        let lat = 0,
          lng = 0,
          pm25 = 0;
        const latEntry = stat.mtDataList.find(v => v.mtName === 'LAT');
        if (!latEntry) continue;

        const lngEntry = stat.mtDataList.find(v => v.mtName === 'LNG');
        if (!lngEntry) continue;

        lat = latEntry.value;
        lng = lngEntry.value;

        const pm25Entry = stat.mtDataList.find(v => v.mtName === 'PM25');

        if (!pm25Entry) continue;
        pm25 = pm25Entry.value;

        const iconUrl = getIconUrl(pm25);
        ret.push({
          title: this.mMap.get(stat.monitor).desc,
          position: { lat, lng },
          pm25,
          infoText: `<strong>${this.mMap.get(stat.monitor).desc}</strong>`,
          iconUrl,
        });
        count++;
      }

      return ret;
    },
  },
  async mounted() {
    const legend = document.getElementById('legend');
    this.$refs.mapRef.$mapPromise.then(map => {
      map.controls[google.maps.ControlPosition.LEFT_CENTER].push(legend);
    });

    /*
    this.loader = this.$loading.show({
      // Optional parameters
      container: null,
      canCancel: false,
    }); */

    await this.fetchMonitors();
    this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 60000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    toggleInfoWindow(marker, idx) {
      this.infoWindowPos = marker.position;
      this.infoOptions.content = marker.infoText;

      //check if its the same marker that was selected if yes toggle
      if (this.currentMidx == idx) {
        this.infoWinOpen = !this.infoWinOpen;
      }

      //if different marker set infowindow to open and reset current marker index
      else {
        this.infoWinOpen = true;
        this.currentMidx = idx;
      }
    },
    getPM25Class(v) {
      if (v < 12) return { FPMI1: true };
      else if (v < 24) return { FPMI2: true };
      else if (v < 36) return { FPMI3: true };
      else if (v < 42) return { FPMI4: true };
      else if (v < 48) return { FPMI5: true };
      else if (v < 54) return { FPMI6: true };
      else if (v < 59) return { FPMI7: true };
      else if (v < 65) return { FPMI8: true };
      else if (v < 71) return { FPMI9: true };
      else return { FPMI10: true };
    },
    async refresh() {
      this.getTodaySummary();
      this.getRealtimeStatus();
    },
    async getRealtimeStatus() {
      const ret = await axios.get('/RealtimeStatus');
      this.realTimeStatusRaw = ret.data;
    },
    async getTodaySummary() {
      const res = await axios.get('/SensorSummary');
      const ret = res.data;
      this.sensorGroupSummary = ret;
      this.drawChart1(ret);
      this.drawChart2(ret);
    },
    drawChart1(ret) {
      let series = [
        {
          name: `群組感測器`,
          colorByPoint: true,
          data: [],
        },
      ];
      for (let group of ret) {
        series[0].data.push({
          name: `${group.name}`,
          y: group.count,
        });
      }

      let chart1 = {
        chart: {
          plotBackgroundColor: null,
          plotBorderWidth: null,
          plotShadow: false,
          type: 'pie',
        },
        colors: [
          '#7CB5EC',
          '#434348',
          '#90ED7D',
          '#F7A35C',
          '#8085E9',
          '#F15C80',
          '#E4D354',
          '#2B908F',
          '#FB9FA8',
          '#91E8E1',
          '#7CB5EC',
          '#80C535',
          '#969696',
        ],
        title: {
          text: '群組總數',
        },
        tooltip: {
          pointFormat: '{series.name}: <b>{point.percentage:.1f}%</b>',
        },
        accessibility: {
          point: {
            valueSuffix: '%',
          },
        },
        plotOptions: {
          pie: {
            allowPointSelect: true,
            cursor: 'pointer',
            dataLabels: {
              enabled: false,
            },
            showInLegend: true,
          },
        },
        series,
      };
      highcharts.chart('chart_container1', chart1);
    },
    drawChart2(ret) {
      let expected = 0,
        below = 0;
      for (let group of ret) {
        expected += group.expected;
        below += group.below;
      }
      let series = [
        {
          name: `接受狀態`,
          colorByPoint: true,
          data: [
            {
              name: `正常`,
              y: expected,
            },
            {
              name: `低於預期`,
              y: below,
              sliced: true,
            },
          ],
        },
      ];

      let chart = {
        chart: {
          plotBackgroundColor: null,
          plotBorderWidth: null,
          plotShadow: false,
          type: 'pie',
        },
        pie: {
          colors: [
            '#7CB5EC',
            '#434348',
            '#90ED7D',
            '#F7A35C',
            '#8085E9',
            '#F15C80',
            '#E4D354',
            '#2B908F',
            '#FB9FA8',
            '#91E8E1',
            '#7CB5EC',
            '#80C535',
            '#969696',
          ],
        },
        title: {
          text: '資料接受狀態',
        },
        tooltip: {
          pointFormat: '{series.name}: <b>{point.percentage:.1f}%</b>',
        },
        accessibility: {
          point: {
            valueSuffix: '%',
          },
        },
        plotOptions: {
          pie: {
            allowPointSelect: true,
            cursor: 'pointer',
            dataLabels: {
              enabled: false,
            },
            showInLegend: true,
          },
        },
        series,
      };
      highcharts.chart('chart_container2', chart);
    },
    getSummaryDesc(count) {
      if (this.sensorSummary.count != 0) {
        return `${count} (${((count / this.sensorSummary.count) * 100).toFixed(
          2,
        )}%)`;
      } else {
        return '等待資料中';
      }
    },
  },
};
</script>

<style></style>
