/**
 * GMRA-System v1.1
 */
window.globalMap = null;
window.globalInfoWindow = null;
window.currentMapCircles = [];

// Глобальные ссылки на графики и сырые данные
let depthChart = null;
let magChart = null;
let moonSpeedChart = null;
let waterTempChart = null;
let rawChartData = [];

window.initMap = function() {
    window.globalMap = new google.maps.Map(document.getElementById("map"), {
        zoom: 2,
        scrollwheel: false,
        center: { lat: 0, lng: 160 },
        restriction: {
            latLngBounds: {
                north: 85,
                south: -85,
                west: -180,
                east: 180
            },
            strictBounds: true
        },
        styles: [
            { elementType: "geometry", stylers: [{ color: "#242f3e" }] },
            { elementType: "labels.text.stroke", stylers: [{ color: "#242f3e" }] },
            { elementType: "labels.text.fill", stylers: [{ color: "#746855" }] },
            { featureType: "water", elementType: "geometry", stylers: [{ color: "#17263c" }] }
        ],
        disableDefaultUI: true
    });

    window.globalInfoWindow = new google.maps.InfoWindow();
    window.globalMap.addListener("click", () => {
        window.globalInfoWindow.close();
    });
    loadMapDataForDate(null);
};

function loadMapDataForDate(targetDateStr) {
    if (!window.globalMap) return;
    window.currentMapCircles.forEach(circle => circle.setMap(null));
    window.currentMapCircles = [];
    window.globalInfoWindow.close();

    let urlUSGS;
    let urlEMSC;
    let referenceTimeMs;
    const threeDaysMs = 3 * 24 * 60 * 60 * 1000;

    if (!targetDateStr) {
        // REAL TIME
        urlUSGS = 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/1.0_week.geojson';
        urlEMSC = 'https://www.seismicportal.eu/fdsnws/event/1/query?limit=1000&format=json&minmag=1.0';
        referenceTimeMs = new Date().getTime();
    } else {
        // HISTORY
        let targetDateObj = new Date(targetDateStr);
        referenceTimeMs = targetDateObj.getTime() + (24 * 60 * 60 * 1000); 
        let startDateObj = new Date(referenceTimeMs - threeDaysMs);
        let startStr = startDateObj.toISOString().split('T')[0];
        let endStr = new Date(referenceTimeMs).toISOString().split('T')[0];
        
        urlUSGS = `https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&starttime=${startStr}&endtime=${endStr}&minmagnitude=1.0`;
        urlEMSC = `https://www.seismicportal.eu/fdsnws/event/1/query?format=json&start=${startStr}&end=${endStr}&minmag=1.0`;
    }
    Promise.allSettled([
        $.ajax({ url: urlUSGS, method: 'GET' }),
        $.ajax({ url: urlEMSC, method: 'GET' })
    ]).then(results => {
        let usgsData = results[0].status === 'fulfilled' ? results[0].value.features : [];
        let emscData = results[1].status === 'fulfilled' ? results[1].value.features : [];
        
        if (usgsData.length === 0 && emscData.length === 0) {
            console.error("Both USGS and EMSC failed or returned no data.");
            return;
        }
        
        let processedEvents = [];
        // Function for calculating distance in kilometers using the haversine formula
        function getDistanceFromLatLonInKm(lat1, lon1, lat2, lon2) {
            var R = 6371; 
            var dLat = (lat2 - lat1) * Math.PI / 180;
            var dLon = (lon2 - lon1) * Math.PI / 180;
            var a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * 
                    Math.sin(dLon/2) * Math.sin(dLon/2);
            var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            return R * c;
        }
        function renderEvent(feature, source) {
            const time = new Date(feature.properties.time).getTime();
            const ageMs = referenceTimeMs - time;
            if (ageMs < 0 || ageMs > threeDaysMs) return;

            const mag = feature.properties.mag;
            const coords = feature.geometry.coordinates; // [lon, lat, depth]
            
            // --- DEDUPLICATION ---
            // If this is an EMSC event, 
            // check if there already one USGS.
          if (source === 'EMSC') {
                let skipEvent = processedEvents.some(prevEvent => {
                    let timeDiffMin = Math.abs(prevEvent.time - time) / 60000;
                    let distKm = getDistanceFromLatLonInKm(coords[1], coords[0], prevEvent.lat, prevEvent.lon);
                    
                    // 1. Duplicate (USGS and EMSC databases): < 15 min and < 300 km
                    let isDuplicate = (timeDiffMin < 15 && distKm < 300);
                    
                    // 2. Swarm/Aftershock (EMSC): < 360 min (3 hours) and < 200 km
                    let isAftershock = (timeDiffMin < 360 && distKm < 300);
                    
                    return isDuplicate || isAftershock;
                });
                
                if (skipEvent) return;
            }
            processedEvents.push({ time: time, lat: coords[1], lon: coords[0] });

            //--- DRAWING ---
            const opacity = Math.max(0.15, 1.0 - Math.pow(ageMs / threeDaysMs, 2));
            let radius = Math.pow(mag, 2.5) * 5500;
            const latRad = coords[1] * (Math.PI / 180);
            radius = radius * Math.cos(latRad);
            
            let color, fillOpac;
            if (mag >= 6.5) { color = '#ff3300'; fillOpac = opacity * 0.6; } 
            else if (mag >= 5.6 && mag < 6.5) { color = '#ff8d00'; fillOpac = opacity * 0.5; } 
            else if (mag >= 5.0 && mag < 5.6) { color = '#fbff00'; fillOpac = opacity * 0.5; } 
            else { color = '#00e5ff'; fillOpac = opacity * 0.3; }

            const circle = new google.maps.Circle({
                strokeColor: color,
                strokeOpacity: opacity,
                strokeWeight: mag > 5.0 ? 2 : 0.5,
                fillColor: color,
                fillOpacity: fillOpac,
                map: window.globalMap,
                center: { lat: coords[1], lng: coords[0] },
                radius: radius
            });
            window.currentMapCircles.push(circle);

            circle.addListener("click", () => {
                let placeName = feature.properties.place || feature.properties.flynn_region || 'Unknown Location';
                const contentString = `
                    <div class="custom-infowindow">
                        <strong style="color: #ffffff; font-size: 1.1em; display:block; margin-bottom: 8px; border-bottom: 1px solid #2f364a; padding-bottom: 4px;">
                            ${placeName}
                        </strong>
                        <span style="color: ${color}; font-weight: bold;">Mag: ${mag.toFixed(1)}</span> | Depth: ${coords[2]} km<br>
                        <span style="font-size: 0.9em; opacity: 0.8; margin-top: 5px; display: block;">
                            ${new Date(time).toISOString().replace('T', ' ').substring(0, 19)} UTC <span style="color: #1e90ff; margin-left: 5px;">[${source}]</span>
                        </span>
                    </div>
                `;
                window.globalInfoWindow.setContent(contentString);
                window.globalInfoWindow.setPosition(circle.getCenter());
                window.globalInfoWindow.open(window.globalMap);
            });
        }
        usgsData.forEach(f => renderEvent(f, 'USGS'));
        emscData.forEach(f => renderEvent(f, 'EMSC'));

    });
}

$(document).ready(function() {
    setServerClock();

    $.ajaxSetup({
        error: function(jqXHR, textStatus) {
            if (jqXHR.status === 503) {
                $('#threat-text').text("CALIBRATING...");
                $('.threat-level').css('background', '#a9b5c9');
                $('#triggers-list').html('<li style="color:var(--accent-yellow);">System is currently recalibrating weights and absorbing new data. Please try again in 1 minute...</li>');
                setTimeout(() => { window.location.reload(); }, 60000);
            } else {
                console.error("API Error: " + textStatus);
            }
        }
    });

    Chart.defaults.color = '#a9b5c9';
    Chart.defaults.borderColor = '#2f364a';

    const commonOptions = {
        responsive: true, 
        maintainAspectRatio: false,
        interaction: {
            mode: 'index',
            intersect: false,
        },
        plugins: { 
            legend: { display: false },
            tooltip: {
                backgroundColor: 'rgba(20, 28, 47, 0.95)',
                titleColor: '#a9b5c9',
                bodyColor: '#ffffff',
                borderColor: '#2f364a',
                borderWidth: 1,
                padding: 10,
                displayColors: false,
                callbacks: {
                    label: function(context) {
                        return context.parsed.y !== null ? context.parsed.y.toFixed(2) : 'No data';
                    }
                }
            }
        },
        scales: { 
            x: { 
                grid: { display: false },
                ticks: {
                    maxRotation: 0,
                    minRotation: 0,
                    autoSkip: true,
                    maxTicksLimit: 8,
                    color: '#6c7a9c'
                }
            }, 
            y: { 
                beginAtZero: false, 
                grid: { color: 'rgba(47, 54, 74, 0.3)' },
                ticks: { color: '#6c7a9c' }
            } 
        },
        elements: { 
            point: { 
                radius: 0,
                hitRadius: 10,
                hoverRadius: 4,
                hoverBackgroundColor: '#ffffff'
            } 
        }
    };

    // Инициализация графиков
    const depthCanvas = document.getElementById('depthChart');
    if (depthCanvas) {
        depthChart = new Chart(depthCanvas, {
            type: 'line',
            data: { labels: [], datasets: [{ data: [], borderColor: '#1e90ff', tension: 0.4, borderWidth: 2 }] },
            options: commonOptions
        });
    }

    const magCanvas = document.getElementById('magChart');
    if (magCanvas) {
        magChart = new Chart(magCanvas, {
            type: 'line',
            data: { labels: [], datasets: [{ data: [], borderColor: '#ff4757', tension: 0.4, borderWidth: 2 }] },
            options: commonOptions
        });
    }

    const moonCanvas = document.getElementById('moonSpeedChart');
    if (moonCanvas) {
        moonSpeedChart = new Chart(moonCanvas, {
            type: 'bar',
            data: { labels: [], datasets: [{ data: [], backgroundColor: '#1e90ff' }] },
            options: commonOptions
        });
    }

    const waterCanvas = document.getElementById('waterTempChart');
    if (waterCanvas) {
        const waterOptions = $.extend(true, {}, commonOptions);
        waterOptions.spanGaps = false; // Разрыв линии, если значение null
        waterOptions.plugins.tooltip.callbacks.label = function(context) {
            return context.parsed.y !== null ? context.parsed.y.toFixed(2) + ' °C' : 'No data';
        };

        waterTempChart = new Chart(waterCanvas, {
            type: 'line',
            data: { labels: [], datasets: [{ data: [], borderColor: '#00e5ff', tension: 0.4, borderWidth: 2 }] },
            options: waterOptions
        });
    }

    $('.groupBy').change(function() {
        renderAggregatedCharts($(this).val());
    });

    $('.timeRange').change(function() {
        let selectedDate = $('#forecast-date').val();
        loadChartData($(this).val(), selectedDate);
    });

    $('#btn-check-forecast').click(function() {
        let $btn = $(this);$btn.css({ 'opacity': '0.5', 'pointer-events': 'none' });
        setTimeout(() => { 
            $btn.css({ 'opacity': '1', 'pointer-events': 'auto' });
        }, 1000);

        let selectedDate = $('#forecast-date').val();
        if (selectedDate) {
            loadForecastData(selectedDate);
            loadMapDataForDate(selectedDate); 
            let currentDays = $('.timeRange').val() || 7;
            loadChartData(currentDays, selectedDate);
        }
    });

    let today = new Date().toISOString().split('T')[0];
    $('#forecast-date').attr('max', today).val(today);
    loadForecastData(today);
    loadChartData($('.timeRange').val() || 7, today);

    function toggleSpoiler(e) {
        if (e) e.preventDefault();
        const content = $('#about-content');
        const icon = $('.spoiler-icon');
        content.slideToggle(300);
        icon.toggleClass('open');       
        setTimeout(() => {
            if (!icon.hasClass('open')) {
                $('html, body').animate({ scrollTop: 0 }, 300);             } else {$('html, body').animate({ scrollTop: 3000 }, 300);
            }
        }, 300);
    }

    $('#spoiler-toggle').on('click', toggleSpoiler);
    $('#about-link').on('click', toggleSpoiler);
});

function getThreatColor(prob) {
    if (prob > 75) return 'var(--accent-red)';
    if (prob > 55) return 'var(--accent-orange)';
    if (prob >= 0) return 'var(--accent-yellow)';
    return 'var(--accent-green)';
}

function loadForecastData(targetDate = '') {
    let url = '/api/analytics/forecast';
    if (targetDate) url += '?date=' + targetDate;

    $.ajax({
        url: url,
        method: 'GET',
        success: function(response) {
            function updateBar(idPrefix, data) {
                if (!data) return;
                let prob = data.probabilityPercent;
                $(`#val-${idPrefix}`).text(prob + '%');
                $(`#bar-${idPrefix}`).css({
                    'width': prob + '%',
                    'background': getThreatColor(prob)
                }).removeClass('progress-red');
            }

            updateBar('today', response.today);
            updateBar('tomorrow', response.tomorrow);
            updateBar('7days', response.days7);
            
            if (response.today) {
                let probToday = response.today.probabilityPercent;                    
                $('.threat-level').css({
                    'background': getThreatColor(probToday),
                    'color': probToday >= 0 && probToday <= 55 ? '#000' : '#fff'
                });
                let triggersHtml = '';
                response.today.activeTriggers.forEach(function(trigger) {
                    triggersHtml += `<li>${trigger}</li>`;
                });
                if (triggersHtml === '') triggersHtml = '<li style="color:#6c7a9c;">Background is normal</li>';
                $('#triggers-list').html(triggersHtml);
            }
        },
        error: function() { 
            console.error("Error loading forecast"); 
        }
    });
}

function loadChartData(days, targetDate) {
    let url = '/api/analytics/charts?days=' + days;
    if (targetDate) {
        url += '&endDate=' + targetDate;
    }
    $.ajax({
        url: url,
        method: 'GET',
        success: function(response) {
            rawChartData = response || [];
            if (rawChartData.length > 0) {
                const latestData = rawChartData[rawChartData.length - 1];               
                if (latestData.avg_distance) {
                    let formattedDist = Math.round(latestData.avg_distance).toLocaleString('en-EN').replaceAll(",", " ") + ' km';
                    $('#moon-dist').text(formattedDist);
                }
            }
            let currentGroupBy = $('.groupBy').val() || 'day';
            renderAggregatedCharts(currentGroupBy);
        },
        error: function() { 
            console.error("Error loading chart data"); 
        }
    });
}

function renderAggregatedCharts(groupBy) {
    if (!rawChartData || rawChartData.length === 0) return;
    if (!groupBy) groupBy = 'day';

    let aggregated = [];
    let lastValidTemp = null;

    if (groupBy === 'day') {
        aggregated = rawChartData.map(row => {
            let tempVal = (row.median_temp !== undefined && row.median_temp !== null) ? Number(row.median_temp) : null;
            if (tempVal !== null && !isNaN(tempVal)) {
                lastValidTemp = tempVal;
            }
            return {
                label: row.stat_date,
                depth: row.avg_depth,
                mag:   row.sum_magnitude,
                speed: row.avg_speed,
                temp:  tempVal
            };
        });
    } else {
        const groupedMap = new Map();
        rawChartData.forEach(row => {
            const dateParts = row.stat_date.split('-'); 
            let key = (groupBy === 'month') ? (dateParts[0] + '-' + dateParts[1]) : dateParts[0];
            
            if (!groupedMap.has(key)) {
                groupedMap.set(key, {
                    count: 0, sumDepth: 0, totalMag: 0, sumSpeed: 0,
                    tempValues: []
                });
            }                
            const group = groupedMap.get(key);
            group.count++;
            group.sumDepth += (row.avg_depth || 0);
            group.totalMag += (row.sum_magnitude || 0);
            group.sumSpeed += (row.avg_speed || 0);

            if (row.median_temp !== undefined && row.median_temp !== null) {
                let parsedTemp = Number(row.median_temp);
                if (!isNaN(parsedTemp)) {
                    group.tempValues.push(parsedTemp);
                    lastValidTemp = parsedTemp;
                }
            }
        });
        
        groupedMap.forEach((val, key) => {
            let avgTemp = val.tempValues.length > 0 ? (val.tempValues.reduce((a, b) => a + b, 0) / val.tempValues.length) : null;
            aggregated.push({
                label: key,
                depth: val.count > 0 ? (val.sumDepth / val.count) : 0,
                mag:   val.totalMag,
                speed: val.count > 0 ? (val.sumSpeed / val.count) : 0,
                temp:  avgTemp
            });
        });
    }

    const labels = aggregated.map(item => item.label);

    if (depthChart) {
        depthChart.data.labels = labels;
        depthChart.data.datasets[0].data = aggregated.map(item => item.depth);
        depthChart.update();
    }
    if (magChart) {
        magChart.data.labels = labels;
        magChart.data.datasets[0].data = aggregated.map(item => item.mag);
        magChart.update();
    }
    if (moonSpeedChart) {
        moonSpeedChart.data.labels = labels;
        moonSpeedChart.data.datasets[0].data = aggregated.map(item => item.speed);
        moonSpeedChart.update();
    }
    if (waterTempChart) {
        waterTempChart.data.labels = labels;
        waterTempChart.data.datasets[0].data = aggregated.map(item => item.temp);
        waterTempChart.update();
    }

    if (lastValidTemp !== null) {
        $('#current-water-temp').text(lastValidTemp.toFixed(2));
    } else {
        $('#current-water-temp').text('--.--');
    }
}

function toggleFullscreen(buttonElement) {
    const $button =$(buttonElement);
    const $panel =$button.closest('.panel');
    const panelNode = $panel[0];
    if (!document.fullscreenElement) {
        if (panelNode.requestFullscreen) {
            $panel.find('.chart-container').css('max-height', '100vh');
            panelNode.requestFullscreen().catch(err => {
                console.error("ERROR Fullscreen:", err);
            });
        }
    } else {
        if (document.exitFullscreen) { 
            document.exitFullscreen(); 
        }
    }
}

$(document).on('fullscreenchange', function() {
    if (!document.fullscreenElement) {
        $('.panel').each(function() {$(this).find('.chart-container').css('max-height', '149px');
        });
        setTimeout(() => {
            if (typeof Chart !== 'undefined' && Chart.instances) {
                $.each(Chart.instances, function(id, chart) { chart.resize(); });
            }
        }, 50);
    }
});

function setServerClock() {
    const $clock =$('#server-clock');
    if ($clock.length) {
        let serverTime = $clock.data('server-time') || Date.now();
        function updateClock() {
            const date = new Date(serverTime);
            $clock.text(date.toISOString().replace('T', ' ').substring(0, 19) + ' UTC');
            serverTime += 1000;
        }
        updateClock();
        setInterval(updateClock, 1000);
    }
}