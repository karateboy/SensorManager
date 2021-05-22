<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col>
            <b-form-file
              v-model="form.uploadFile"
              :state="Boolean(form.uploadFile)"
              accept=".csv"
              browse-text="..."
              placeholder="選擇上傳檔案..."
              drop-placeholder="拖曳檔案至此..."
            ></b-form-file>
          </b-col>
        </b-row>
        <br />
        <b-row>
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
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
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import { mapMutations } from 'vuex';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import 'vue-loading-overlay/dist/vue-loading.css';

export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    return {
      actorName: '',
      form: {
        uploadFile: '',
      },
      timer: 0,
    };
  },
  computed: {},
  beforeDestroy() {
    clearTimeout(this.timer);
  },
  methods: {
    ...mapMutations(['setLoading']),
    upload() {
      let file: string = this.form.uploadFile;

      var formData = new FormData();
      formData.append('data', file);
      this.setLoading({ loading: true, message: '資料上傳中' });
      axios
        .post('/sensorData', formData, {
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
        .catch(err => alert(err));
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
  },
});
</script>

<style></style>
