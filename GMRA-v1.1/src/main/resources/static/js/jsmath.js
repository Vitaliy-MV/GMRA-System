/**
 * GMRA-Sysstem v1.0
 */
$(document).ready(function() {
	setServerClock();
    $.ajaxSetup({
        error: function(jqXHR, textStatus, errorThrown) {
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
        interaction: { mode: 'index', intersect: false },
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
                    label: function(context) { return context.parsed.y.toFixed(2); }
                }
            }
        },
        scales: { 
            x: { 
                grid: { display: false },
                ticks: { maxRotation: 0, minRotation: 0, autoSkip: true, maxTicksLimit: 8, color: '#6c7a9c' }
            }, 
            y: { 
                beginAtZero: false, 
                grid: { color: 'rgba(47, 54, 74, 0.3)' },
                ticks: { color: '#6c7a9c' }
            } 
        },
        elements: { 
            point: { radius: 0, hitRadius: 10, hoverRadius: 4, hoverBackgroundColor: '#ffffff' } 
        }
    };
    let depthChart = new Chart(document.getElementById('depthChart'), {
        type: 'line',
        data: { labels: [], datasets: [{ data: [], borderColor: '#1e90ff', tension: 0.4, borderWidth: 2 }] },
        options: commonOptions
    });

    let magChart = new Chart(document.getElementById('magChart'), {
        type: 'line',
        data: { labels: [], datasets: [{ data: [], borderColor: '#ff4757', tension: 0.4, borderWidth: 2 }] },
        options: commonOptions
    });

    let moonSpeedChart = new Chart(document.getElementById('moonSpeedChart'), {
        type: 'bar',
        data: { labels: [], datasets: [{ data: [], backgroundColor: '#1e90ff' }] },
        options: commonOptions
    });
    $('.groupBy').change(function() {
        renderAggregatedCharts($(this).val(), depthChart, magChart, moonSpeedChart);
    });
    $('.timeRange').change(function() {
        let selectedDate = $('#forecast-date').val();
        loadChartData($(this).val(), selectedDate, depthChart, magChart, moonSpeedChart);
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
                    /*
                    let threatText = "YELLOW";   
                    if (probToday > 75) threatText = "RED";
                    else if (probToday > 50) threatText = "ORANGE";
                    else if (probToday > 35) threatText = "YELLOW";
                    $('#threat-text').text(threatText);
                    */
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
            error: function() { console.error("Error loading forecast"); }
        });
    }
    let isChecking = false;
    $('#btn-check-forecast').click(function() {
        if (isChecking) return; 
        isChecking = true;
        let $btn = $(this); 
        $btn.css('opacity', '0.5').css('pointer-events', 'none');
        
        setTimeout(() => { 
            isChecking = false; 
            $btn.css('opacity', '1').css('pointer-events', 'auto');
        }, 1000);
        
        let selectedDate = $('#forecast-date').val();
        if (selectedDate) {
            loadForecastData(selectedDate);
            // Удален вызов loadMapDataForDate!
            let currentDays = $('.timeRange').val() || 7;
            loadChartData(currentDays, selectedDate, depthChart, magChart, moonSpeedChart);
        }
    });
    let today = new Date().toISOString().split('T')[0];
    $('#forecast-date').attr('max', today);
    $('#forecast-date').val(today);
    
    loadForecastData(today);
    loadChartData($('.timeRange').val() || 7, today, depthChart, magChart, moonSpeedChart);

    function toggleSpoiler(e) {
        if (e) e.preventDefault();
        const content = $('#about-content');
        const icon = $('.spoiler-icon');
        content.slideToggle(300);
        icon.toggleClass('open');       
        setTimeout(() => {
            if (!icon.hasClass('open')){ $('html, body').animate({scrollTop: 0}, 300);}
            else {$('html, body').animate({scrollTop: 3000}, 300);}	
        }, 300);
    }
    $('#spoiler-toggle').on('click', toggleSpoiler);
    $('#about-link').on('click', toggleSpoiler);
});

var rawChartData = [];
function loadChartData(days, targetDate, depthChart, magChart, moonSpeedChart) {
    let url = '/api/analytics/charts?days=' + days;
    if (targetDate) url += '&endDate=' + targetDate;
    
    $.ajax({
        url: url,
        method: 'GET',
        success: function(response) {
            rawChartData = response;
            if (response && response.length > 0) {
                const latestData = response[response.length - 1];               
                if (latestData.avg_distance) {
                    const dist = $("#moon-dist").val();
                    if (dist == 0){
                        let formattedDist = Math.round(latestData.avg_distance).toLocaleString('en-EN') + ' km';
                        formattedDist = formattedDist.replaceAll(",", " ");
                        $('#moon-dist').text(formattedDist);
                    }
                }
            }
            let currentGroupBy = $('.groupBy').val();
            renderAggregatedCharts(currentGroupBy, depthChart, magChart, moonSpeedChart);
        },
        error: function() { console.error("Error loading chart data"); }
    });
}

function renderAggregatedCharts(groupBy, depthChart, magChart, moonSpeedChart) {
    if (!rawChartData || rawChartData.length === 0) return;
    if (groupBy === null) groupBy = 'day';
    
    let aggregated = [];
    if (groupBy === 'day') {
        aggregated = rawChartData.map(row => ({
            label:  row.stat_date,
            depth:  row.avg_depth,
            mag:    row.sum_magnitude,
            speed:  row.avg_speed
        }));
    } else {
        const groupedMap = new Map();
        rawChartData.forEach(row => {
            let key;
            const dateParts = row.stat_date.split('-'); 
            if (groupBy === 'month') { key = dateParts[0]+'-'+dateParts[1]; }
            else if (groupBy === 'year') { key = dateParts[0]; }
            
            if (!groupedMap.has(key)) {
                groupedMap.set(key, { count: 0, sumDepth: 0, totalMag: 0, sumSpeed: 0 });
            }                
            const group = groupedMap.get(key);
            group.count++;
            group.sumDepth += row.avg_depth;
            group.totalMag += row.sum_magnitude;
            group.sumSpeed += row.avg_speed;
        });
        groupedMap.forEach((val, key) => {
            aggregated.push({
                label: key,
                depth: val.sumDepth / val.count,
                mag: val.totalMag,
                speed: val.sumSpeed / val.count
            });
        });
    }
    
    const labels = aggregated.map(item => item.label);
    depthChart.data.labels = magChart.data.labels = moonSpeedChart.data.labels = labels;
    depthChart.data.datasets[0].data = aggregated.map(item => item.depth);
    magChart.data.datasets[0].data = aggregated.map(item => item.mag);
    moonSpeedChart.data.datasets[0].data = aggregated.map(item => item.speed);

    depthChart.update();
    magChart.update();
    moonSpeedChart.update();
}

// Полноэкранный режим графиков
function toggleFullscreen(buttonElement) {
    const $button = $(buttonElement);
    const $panel = $button.closest('.panel');
    const panelNode = $panel[0];
    if (!document.fullscreenElement) {
        if (panelNode.requestFullscreen) {
            $panel.find('.chart-container').css('max-height', '100vh');
            panelNode.requestFullscreen().catch(err => { console.error("ERROR Fullscreen:", err); });
        }
    } else {
        if (document.exitFullscreen) { document.exitFullscreen(); }
    }
}

$(document).on('fullscreenchange', function() {
    if (!document.fullscreenElement) {
        $('.panel').each(function() {
            $(this).find('.chart-container').css('max-height', '149px');
        });
        setTimeout(() => {
            if (typeof Chart !== 'undefined' && Chart.instances) {
                $.each(Chart.instances, function(id, chart) { chart.resize(); });
            }
        }, 50);
    }
});

function setServerClock(){
    const $clock = $('#server-clock');
    if ($clock.length) {
        let serverTime = $clock.data('server-time') || Date.now();
        function updateClock() {
            const date = new Date(serverTime);
            const formattedTime = date.toISOString().replace('T', ' ').substring(0, 19) + ' UTC';
            $clock.text(formattedTime);
            serverTime += 1000;
        }
        updateClock();
        setInterval(updateClock, 1000);
    }
}